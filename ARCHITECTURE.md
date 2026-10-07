# Architecture

`nextcloud-backup-manager` is a Java 21 command-line application. An external scheduler starts one process per backup; the application exits when its workflow finishes. `BackupApplication` coordinates the services, and `BackupContext` loads configuration and constructs them without a dependency injection framework.

## Source layout

```text
src/main/java/dev/irattiz/backup/
├── BackupApplication.java       # CLI entry point and workflow
├── config/BackupConfig.java     # Properties loading, defaults, validation
├── context/BackupContext.java   # Composition root
├── exception/BackupException.java
├── logging/BackupLogger.java    # SLF4J/Logback setup and run summaries
├── service/
│   ├── DriveService.java
│   ├── NextcloudService.java
│   ├── NextcloudBackupService.java
│   ├── ResticService.java
│   ├── NotifierService.java
│   └── DatabaseService.java     # Supplementary direct mysqldump support
└── util/ShellCommand.java       # Subprocess execution
```

Tests live under `src/test/java/dev/irattiz/backup`. The Gradle `test` task excludes tests tagged `integration`; `integrationTest` runs those tagged tests and depends on the shaded JAR.

## Responsibilities

- `BackupConfig` loads `<project-root>/config/backup.conf`, applies Nextcloud Snap defaults, validates required settings, and exposes typed values. Environment `RESTIC_PROGRESS_FPS` overrides the file. Dry-run precedence is `DRY_RUN` environment variable, `--dry-run` system property, config value, then `false`.
- `BackupContext` creates the configuration, configures logging, constructs the services, and performs startup initialization for Restic and the Snap export directory.
- `ShellCommand` owns subprocess creation, output handling, timeouts, and command-level dry-run behavior. Services use it for external programs.
- `DriveService` mounts the configured device by UUID when needed, checks writability, and requires at least 5 GiB free space. Drive validation is skipped in dry-run mode.
- `NextcloudService` checks the configured OCC executable, toggles maintenance mode, and attempts to disable maintenance mode from a JVM shutdown hook. OCC commands use `NEXTCLOUD_OCC_TIMEOUT`, defaulting to 30 seconds.
- `NextcloudBackupService` runs `nextcloud.export -b`, detects the newly created entry under `NEXTCLOUD_BACKUP_DIR`, and removes the export directory contents after the Restic backup.
- `ResticService` checks its prerequisites, initializes and unlocks the repository, backs up the data, config, and export paths, applies tag-scoped daily/weekly/monthly retention, reports the latest snapshot, and logs repository stats.
- `NotifierService` sends `START`, `SUCCESS`, and best-effort `FAILURE` events through `home-lab-notifier.jar`.
- `DatabaseService` provides supplementary direct Snap `mysqldump` support. The normal workflow does not call it.
- `BackupLogger` configures the console and daily rolling file appenders. Files are written under `<project-root>/log/` and retained for 90 days.

## Workflow

```text
Load config and initialize services
  → START notification
  → register maintenance cleanup hook
  → validate OCC and backup drive
  → enable maintenance mode
  → export Nextcloud database with nextcloud.export -b
  → initialize/unlock Restic and back up data, config, and export
  → prune snapshots by host and RESTIC_TAG
  → clean up exports and disable maintenance mode
  → SUCCESS notification, summary, and repository stats
```

Workflow failures are logged and trigger a best-effort `FAILURE` notification. The shutdown hook attempts maintenance-mode cleanup. Dry-run mode skips drive checks, maintenance changes, external command execution, and notifier calls; startup still loads and validates configuration, checks the Restic password file, creates the Snap export directory, and writes logs. The workflow still runs filesystem export cleanup, which deletes the contents of the configured export directory. Do not point dry-run at an export directory containing files that must be preserved.

## Runtime configuration

Configuration examples are in [`README.md`](README.md); the checked-in [`config/backup.conf`](config/backup.conf) is a host-specific template, and built-in defaults are defined in `BackupConfig`. Path keys ending in `_DIR`, `_FILE`, `_REPOSITORY`, or `_MOUNT` must be absolute. Retention counts must be positive integers; `RESTIC_TAG` is required and cannot be blank. `NEXTCLOUD_DATA_DIR`, `NEXTCLOUD_CONFIG_DIR`, and `NEXTCLOUD_BACKUP_DIR` default to the Snap paths when omitted.

The project root is derived from the application code location. For a Gradle artifact under `build/libs`, it resolves to the repository root; for an installed JAR, it resolves to the JAR's containing directory. Configuration is loaded from that root, not from the process working directory.
