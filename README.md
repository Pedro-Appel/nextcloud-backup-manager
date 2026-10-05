# nextcloud-backup-manager

Automated backup service for an Ubuntu Server running Nextcloud (Snap). Manages drive validation,
Nextcloud maintenance mode, MySQL database export, Restic snapshots, retention, and notifications.

Version: 1.0.0

## Features

- External drive mount validation and writability/space checks
- Nextcloud maintenance mode lifecycle via `nextcloud.occ`
- MySQL database dump using the bundled Snap mysqldump
- Restic backup with configurable retention policy
- Dry-run mode (`DRY_RUN=true`) for safe rehearsals
- Structured, colour-coded logging to both console and a daily log file
- External notification via a Java notifier JAR (START / SUCCESS / FAILURE events)
- Automatic cleanup trap — maintenance mode is always disabled on exit

## Requirements

- Ubuntu Server with Nextcloud installed as a Snap package
- `restic` installed and on `$PATH`
- `jq` installed and on `$PATH`
- Java runtime (for the notifier)
- Root privileges to mount drives and run `nextcloud.occ`

## Directory Layout

```
nextcloud-backup-manager/
├── bin/
│   └── backup.sh          # Entry point / orchestrator
├── lib/
│   ├── common.sh           # Bootstrap: path exports, library loader, init sequence
│   ├── config.sh           # Config file loader and validator
│   ├── logging.sh          # Structured logger (DEBUG/INFO/SUCCESS/WARN/ERROR)
│   ├── utils.sh            # Shared helpers (require_*, create_directory, is_dry_run)
│   ├── drive.sh            # Drive mount, writability, and space validation
│   ├── nextcloud.sh        # Nextcloud OCC wrapper and maintenance mode
│   ├── nextcloud_backup.sh # Nextcloud DB export via nextcloud.export
│   ├── database.sh         # MySQL dump via Snap-bundled mysqldump
│   ├── restic.sh           # Restic init, backup, retention, stats
│   └── notifier.sh         # Java JAR notifier (START/SUCCESS/FAILURE)
├── config/
│   └── backup.conf         # Runtime configuration (see Configuration below)
├── tests/
│   ├── test-logging.sh     # Logging module smoke test
│   └── test-database.sh    # Database config-load smoke test
├── VERSION.md
└── README.md
```

Logs are written to `<project_root>/log/backup-YYYY-MM-DD.log`.  
Temporary database dumps land in `<project_root>/database/` and are removed after the Restic backup.

## Configuration

Copy `config/backup.conf` and adjust as needed. All variables are exported into the environment
at startup by `config.sh`.

```bash
# Log verbosity: DEBUG | INFO | WARN | ERROR
LOG_LEVEL=INFO

# External backup drive
BACKUP_MOUNT=/mnt/backup
BACKUP_DEVICE_UUID=<uuid>          # blkid UUID of the backup drive

# Restic
RESTIC_REPOSITORY=/mnt/backup/restic
RESTIC_PASSWORD_FILE=/etc/backup-service/restic.pass
RESTIC_CACHE_DIR=/var/cache/backup
RESTIC_RETENTION_DAILY=7
RESTIC_RETENTION_WEEKLY=4
RESTIC_RETENTION_MONTHLY=12

# Nextcloud (Snap layout defaults; override only if non-standard)
NEXTCLOUD_DATA_DIR=/mnt/nas/nextcloud/data
NEXTCLOUD_CONFIG_DIR=/var/snap/nextcloud/current/nextcloud/config
NEXTCLOUD_OCC=/snap/bin/nextcloud.occ
NEXTCLOUD_OCC_TIMEOUT=30           # seconds before occ calls time out

# Notifier
NOTIFIER_DIR=/opt/home-lab/notifier
```

## Usage

```bash
# Normal run (requires root)
sudo bash bin/backup.sh

# Dry run — no writes, no maintenance mode toggle, no real Restic backup
sudo DRY_RUN=true bash bin/backup.sh

# Override log level at runtime
sudo LOG_LEVEL=DEBUG bash bin/backup.sh
```

## Java Build

The Java CLI is built with the Gradle wrapper and packaged as a self-contained JAR:

```bash
./gradlew shadowJar
```

The artifact is `build/libs/nextcloud-backup-manager-1.0.0-all.jar`. In deployment, keep the JAR
at the project root and the configuration at `config/backup.conf` under that same root.

## Running (Java)

```bash
# Normal run (requires root and the configured system integrations)
sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar

# Rehearse the complete workflow without external commands
sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar --dry-run

# Enable debug logging through backup.conf (LOG_LEVEL=DEBUG)
sudo java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
```

The Java CLI reads `config/backup.conf` relative to the deployment directory. Environment
`DRY_RUN=true` and the `--dry-run` option enable simulation.

## Testing

```bash
./gradlew test
./gradlew integrationTest
```

The integration test runs the complete workflow with dry-run enabled and writes only under a
temporary project directory.

## Backup Workflow

1. Bootstrap (`common_init`) — directories, config, logger, notifier, Restic, Nextcloud
2. Send **START** notification
3. Register `nextcloud_cleanup` as EXIT trap (ensures maintenance mode is always disabled)
4. **Environment Validation** — verify `nextcloud.occ` is present, mount/writability/space check
5. **Consistency boundary** — enable Nextcloud maintenance mode
6. **Database export** — `nextcloud.export -b` creates a Snap-native DB export
7. **Restic backup** — init repo if missing, unlock, backup `data/`, `config/`, and the DB export
8. **Retention policy** — `restic forget --prune` with daily/weekly/monthly limits
9. **Cleanup exports** — remove the Nextcloud export files from `NEXTCLOUD_BACKUP_DIR`
10. **End consistency boundary** — disable maintenance mode
11. Send **SUCCESS** notification with snapshot ID and duration
12. Print summary to log

On any failure the EXIT trap fires, disabling maintenance mode if it is still active.

## Running Tests

Tests are standalone scripts that source the library directly. Set `BACKUP_BASE_DIR` first:

```bash
export BACKUP_BASE_DIR="$(pwd)"
bash tests/test-logging.sh
bash tests/test-database.sh
```

## Future Enhancements

- Systemd service unit and timer
- Nextcloud and Tailscale health checks
- `notifier_failure` wired into the EXIT trap
- Offsite replication
- Restore validation procedure
