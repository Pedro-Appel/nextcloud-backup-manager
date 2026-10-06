# nextcloud-backup-manager

Java 21 command-line application that creates Restic backups of a Snap-based Nextcloud installation. It validates the backup drive and Nextcloud environment, enables maintenance mode, creates a Snap-native database export, snapshots the Nextcloud data and configuration with Restic, applies retention, cleans up exports, and sends lifecycle notifications.

## Prerequisites

- Ubuntu Server with Nextcloud installed as a Snap
- Java 21
- `restic`, `nextcloud.occ`, and `nextcloud.export`
- Root privileges for the configured mount, Nextcloud, and Restic paths
- `home-lab-notifier.jar` in the configured notifier directory

## Quick start

1. Create the Restic password file and restrict access to it:

   ```bash
   sudo install -d -m 0755 /etc/backup-service
   printf '%s\n' '<restic-password>' | sudo tee /etc/backup-service/restic.pass >/dev/null
   sudo chmod 0600 /etc/backup-service/restic.pass
   ```

2. Find the UUID of the backup device:

   ```bash
   sudo blkid
   ```

3. Edit `config/backup.conf`. Set at least `BACKUP_MOUNT`, `BACKUP_DEVICE_UUID`, `RESTIC_REPOSITORY`, `RESTIC_PASSWORD_FILE`, `NEXTCLOUD_OCC`, and `NOTIFIER_DIR`.

   Keep the configuration directory with the JAR layout. For a development JAR at
   `/home/deploy/build/libs/nextcloud-backup-manager.jar`, install the configuration at:

   ```bash
   sudo install -d -m 0755 /home/deploy/config
   sudo install -m 0600 /path/to/backup.conf /home/deploy/config/backup.conf
   ```

4. Build the executable JAR:

   ```bash
   ./gradlew shadowJar
   ```

5. Rehearse the workflow from the repository root:

   ```bash
   sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar --dry-run
   ```

6. Run the backup:

   ```bash
   sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
   ```

The application writes console output and daily files under `log/backup-YYYY-MM-DD.log`.

## Build

```bash
./gradlew test
./gradlew shadowJar
```

The executable artifact is `build/libs/nextcloud-backup-manager-1.0.0-all.jar`. Run the integration test separately with `./gradlew integrationTest`.

## Configuration

The application derives its root from the JAR location and loads `<root>/config/backup.conf`. It does not search the current working directory and dry-run mode still requires a valid configuration file.

Use one of these supported layouts:

```text
# Development build
/home/deploy/
├── config/
│   └── backup.conf
└── build/
    └── libs/
        └── nextcloud-backup-manager-1.0.0-all.jar

# Installed application
/opt/backup/
├── config/
│   └── backup.conf
└── nextcloud-backup-manager.jar
```

For the development layout, a missing configuration is reported as:

```text
FATAL: Cannot read config file: /home/deploy/config/backup.conf
```

Copy `config/backup.conf` to that exact path, update its values for the host, and run the command again. The systemd installation commands in [`deploy/README.md`](deploy/README.md) create the `/opt/backup` layout.

```properties
LOG_LEVEL=INFO

BACKUP_MOUNT=/mnt/backup
BACKUP_DEVICE_UUID=<uuid>

RESTIC_REPOSITORY=/mnt/backup/restic
RESTIC_PASSWORD_FILE=/etc/backup-service/restic.pass
RESTIC_CACHE_DIR=/var/cache/backup
RESTIC_RETENTION_DAILY=7
RESTIC_RETENTION_WEEKLY=4
RESTIC_RETENTION_MONTHLY=12

NEXTCLOUD_DATA_DIR=/var/snap/nextcloud/current/nextcloud/data
NEXTCLOUD_CONFIG_DIR=/var/snap/nextcloud/current/nextcloud/config
NEXTCLOUD_OCC=/snap/bin/nextcloud.occ
NEXTCLOUD_OCC_TIMEOUT=30

NOTIFIER_DIR=/opt/home-lab/notifier
```

`NEXTCLOUD_DATA_DIR`, `NEXTCLOUD_CONFIG_DIR`, and `NEXTCLOUD_BACKUP_DIR` have Snap defaults. Set `NEXTCLOUD_BACKUP_DIR` when the Snap export directory is non-standard. Retention values must be positive integers and path settings must be absolute.

Configuration precedence for dry-run mode is `DRY_RUN` environment variable, `--dry-run`, `DRY_RUN` in `backup.conf`, then `false`.

## Run

Run directly from the repository after building:

```bash
sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
```

Available command-line options:

```text
Usage: nextcloud-backup-manager [--dry-run] [--help]
  --dry-run   Simulate all operations without writing data
  --help      Show this message
```

Dry-run mode skips external commands, drive checks, notifier calls, and maintenance-mode changes. Startup still loads and validates the configuration, checks that the Restic password file exists, creates the configured Nextcloud export directory if necessary, and writes application logs.

If startup reports `Cannot read config file`, use the path printed in the error. Create its parent directory and install the configuration there with mode `0600`; changing the directory from which Java is launched does not change the expected path.

After deployment, run the stable JAR manually with:

```bash
sudo java -jar /opt/backup/nextcloud-backup-manager.jar
```

## Workflow

1. Send a `START` notification.
2. Register shutdown cleanup for Nextcloud maintenance mode.
3. Validate Nextcloud OCC and the backup drive.
4. Enable maintenance mode.
5. Run `nextcloud.export -b` and detect the new export.
6. Initialise and unlock Restic, then back up the Nextcloud data, configuration, and export.
7. Apply daily, weekly, and monthly retention with `restic forget --prune`.
8. Remove exports and disable maintenance mode.
9. Send a `SUCCESS` notification with the snapshot ID and duration.

Failures are logged, reported through a `FAILURE` notification when possible, and trigger best-effort maintenance-mode cleanup.

## Systemd deployment

See [`deploy/README.md`](deploy/README.md) for installation of the JAR, configuration, service unit, and daily timer.

```bash
./gradlew shadowJar
```

Inspect scheduled runs with `systemctl list-timers nextcloud-backup.timer` and service output with `journalctl -u nextcloud-backup.service`.

## Package structure

```text
dev.irattiz.backup
├── BackupApplication.java          CLI entry point and backup workflow
├── config/
│   └── BackupConfig.java           Configuration loading, defaults, and validation
├── context/
│   └── BackupContext.java          Creates and connects application services
├── exception/
│   └── BackupException.java        Application-level checked exception
├── logging/
│   └── BackupLogger.java           Console, file, phase, and summary logging
├── service/
│   ├── DriveService.java           Mount, writability, and free-space checks
│   ├── NextcloudService.java       OCC checks and maintenance mode
│   ├── NextcloudBackupService.java Snap database export and cleanup
│   ├── ResticService.java          Repository, backup, retention, and statistics
│   ├── NotifierService.java        START, SUCCESS, and FAILURE notifications
│   └── DatabaseService.java        Supplementary direct mysqldump support
└── util/
    └── ShellCommand.java           External process execution and timeouts
```

Tests mirror these packages under `src/test/java/dev/irattiz/backup`. `BackupApplicationIT.java` is the end-to-end dry-run integration test.

## Important files

| File | Purpose |
| --- | --- |
| `README.md` | Build, configuration, usage, and project overview |
| `config/backup.conf` | Runtime paths, device UUID, retention rules, logging, and notifier configuration |
| `build.gradle.kts` | Java 21 toolchain, dependencies, tests, application entry point, and shaded JAR configuration |
| `src/main/java/dev/irattiz/backup/BackupApplication.java` | Defines the ordered backup workflow and CLI options |
| `src/main/java/dev/irattiz/backup/config/BackupConfig.java` | Defines required settings, defaults, path validation, and dry-run precedence |
| `src/main/java/dev/irattiz/backup/context/BackupContext.java` | Loads configuration and creates the services used by the workflow |
| `src/main/java/dev/irattiz/backup/util/ShellCommand.java` | Runs system commands, applies timeouts, and implements command-level dry-run behavior |
| `src/main/resources/logback.xml` | Configures coloured console output and 90 days of rolling log files |
| `deploy/nextcloud-backup.service` | Runs the deployed JAR once as root from `/opt/backup` |
| `deploy/nextcloud-backup.timer` | Starts the service every day at 02:00 and catches missed runs |
| `deploy/README.md` | Installation and Jenkins deployment instructions |
| `Jenkinsfile` | Validates, packages, deploys, and optionally enables the staging timer |
| `ARCHITECTURE.md` | Application boundaries and design details |
| `SPEC.md` | Functional requirements and expected behavior |

## Development commands

```bash
# Run unit tests
./gradlew test

# Run the end-to-end dry-run integration test
./gradlew integrationTest

# Run all verification tasks and build the shaded JAR
./gradlew test integrationTest shadowJar

# Remove generated build output
./gradlew clean
```
