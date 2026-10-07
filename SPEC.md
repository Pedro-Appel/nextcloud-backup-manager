# Operational specification

This document describes the current Java 21 application in production. The implementation is the source of truth for behavior; deployment scheduling is defined by the systemd units in `deploy/`.

## Purpose and runtime

The application creates Restic backups of a Snap-based Nextcloud installation. It is a one-shot CLI process with no embedded scheduler or server. The shaded JAR is built with Gradle and reads `config/backup.conf` relative to its resolved project root.

Supported arguments:

```text
Usage: nextcloud-backup-manager [--dry-run] [--help]
  --dry-run   Simulate operations without running subprocesses
  --help      Show this message
```

`--help` exits before configuration is loaded. A normal or dry-run invocation requires a readable, valid config file.

## Required environment

The host must provide Java 21, Restic, the Snap commands `nextcloud.occ` and `nextcloud.export`, and the `home-lab-notifier.jar` file in `NOTIFIER_DIR`. Production runs under the root systemd service because it mounts the backup device and accesses Nextcloud and Restic paths.

Required configuration keys are `BACKUP_MOUNT`, `BACKUP_DEVICE_UUID`, `RESTIC_REPOSITORY`, `RESTIC_PASSWORD_FILE`, `RESTIC_TAG`, `NEXTCLOUD_OCC`, and `NOTIFIER_DIR`. Path settings must be absolute. `RESTIC_TAG` must be non-blank. Retention counts must be positive integers.

`NEXTCLOUD_DATA_DIR`, `NEXTCLOUD_CONFIG_DIR`, and `NEXTCLOUD_BACKUP_DIR` default to `/var/snap/nextcloud/current/nextcloud/data`, `/var/snap/nextcloud/current/nextcloud/config`, and `/var/snap/nextcloud/common/backups/`. `NEXTCLOUD_OCC_TIMEOUT` defaults to 30 seconds when absent, blank, or nonnumeric; a numeric value is used as-is in seconds. `RESTIC_PROGRESS_FPS` defaults to `0.033333`; it must be a finite positive number, and the environment value overrides the file. `RESTIC_CACHE_DIR` may remain in config files for compatibility, but the current application does not pass it to Restic.

Dry-run precedence is `DRY_RUN` environment variable, `--dry-run`, `DRY_RUN` in the config file, then `false`. An explicitly set environment value takes precedence even when it is `false`.

## Backup behavior

The application performs these operations in order:

1. Initialize logging, Restic prerequisites, and the Nextcloud export directory.
2. Send the `START` notifier event.
3. Register a shutdown hook to attempt maintenance-mode cleanup.
4. Check that OCC is executable; mount the backup drive by UUID if needed; check drive writability and require at least 5 GiB free.
5. Enable Nextcloud maintenance mode.
6. Run `nextcloud.export -b` with a ten-minute timeout and detect the new entry in the export directory.
7. Initialize the Restic repository if needed, unlock it, and back up the configured data directory, config directory, and detected export. Backups use JSON progress, `RESTIC_TAG`, and host/tag grouping.
8. Apply `restic forget --prune` with the configured daily, weekly, and monthly retention counts, scoped by tag and host/tag grouping.
9. Delete the contents of the Nextcloud export directory and disable maintenance mode.
10. Send `SUCCESS` with snapshot ID and duration, log a summary, and log Restic stats.

On a workflow failure, the application logs the error, attempts a `FAILURE` notifier event, and exits nonzero. The shutdown hook checks maintenance mode and attempts to disable it. Failures in notification or cleanup do not replace the original workflow error.

## Dry-run behavior

Dry-run mode skips drive validation, OCC checks and changes, subprocess execution, and notifier calls. Startup still validates the config, checks that the Restic password file exists, creates the Nextcloud export directory if needed, configures logging, and writes logs. The workflow also still runs filesystem export cleanup, deleting the contents of the configured Nextcloud export directory. Do not use dry-run with exports in that directory that need to be preserved.

## Logging

Logs go to stderr and to daily files at `<project-root>/log/backup-YYYY-MM-DD.log`. The file appender retains 90 days. Restic backup output is formatted into progress and summary messages at INFO; retention output is logged at INFO, and other subprocess output is generally logged at DEBUG.

## Packaging and scheduling

`./gradlew shadowJar` creates `build/libs/nextcloud-backup-manager-1.0.0-all.jar`. The deployment pipeline renames the artifact to `nextcloud-backup-manager.jar` before staging it.

The checked-in systemd timer runs `nextcloud-backup.service` every Sunday at 03:00 and has `Persistent=false`, so missed activations are not caught up. The service sets `RESTIC_PROGRESS_FPS=0.1`, overriding the config value and requesting progress about every 10 seconds.

## Verification commands

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar
```
