# Architecture

## Overview

`nextcloud-backup-manager` is a Bash-based backup service. It is structured as a single orchestrator
script (`bin/backup.sh`) that sources a set of focused library modules from `lib/`. Each module owns
one concern; `common.sh` wires them together at startup.

---

## Top-Level Structure

```
nextcloud-backup-manager/
├── bin/backup.sh          # Entry point — orchestration only, no business logic
├── lib/                   # Library modules (sourced, not executed)
│   ├── common.sh          # Bootstrap: directory setup, library loader, init sequence
│   ├── config.sh          # Config file loader and validator
│   ├── logging.sh         # Structured logger
│   ├── utils.sh           # Generic helpers used by all other modules
│   ├── drive.sh           # Drive mount and validation
│   ├── nextcloud.sh       # OCC wrapper and maintenance mode
│   ├── nextcloud_backup.sh# Snap-native DB export via nextcloud.export
│   ├── database.sh        # MySQL dump via Snap-bundled mysqldump
│   ├── restic.sh          # Restic repository management and backup
│   └── notifier.sh        # Java JAR notifier
├── config/backup.conf     # All runtime configuration
├── tests/                 # Standalone smoke tests
│   ├── test-logging.sh
│   └── test-database.sh
└── log/                   # Created at runtime; daily log files land here
```

---

## Module Responsibilities

### `bin/backup.sh` — Orchestrator

The entry point. Sets `BACKUP_BASE_DIR`, sources `lib/common.sh` (which sources all other
libraries), and runs `main()`. `main()` is a linear sequence of phase calls; it contains no
business logic of its own. Every phase is wrapped in `log_section` / `log_phase_change` calls for
structured timing output.

### `lib/common.sh` — Bootstrap

Executed first via `source`. Exports all base path variables (`BACKUP_LIB_DIR`, `BACKUP_LOG_DIR`,
etc.), sources the other library files in dependency order, and exposes `common_init()`.

`common_init()` runs:
1. `common_init_directories` — creates `log/` and state directories.
2. `config_init` — loads and validates `backup.conf`.
3. `common_init_logging` — sets `LOG_FILE` and calls `log_init`.
4. `notifier_init` — sources `notifier.sh`.
5. `nextcloud_backup_init` — creates the Nextcloud export directory.
6. `restic_init` + `restic_check` — exports Restic env vars and verifies prerequisites.

Library source order inside `common.sh` (order matters for dependency resolution):

```
utils.sh → logging.sh → config.sh → drive.sh → nextcloud.sh → nextcloud_backup.sh → restic.sh
notifier.sh  (sourced lazily inside notifier_init at runtime, after config is loaded)
```

### `lib/config.sh` — Configuration

Loads `config/backup.conf` via `source`. Uses a `CONFIG_LOADED` guard to prevent double-sourcing.
After loading, `config_autodetect_nextcloud` fills in Snap defaults if `NEXTCLOUD_DATA_DIR` was
not explicitly set. `config_validate` enforces required variables and path constraints (all paths
must be absolute).

### `lib/logging.sh` — Structured Logger

Five public log levels: `DEBUG`, `INFO`, `SUCCESS`, `WARN`, `ERROR`. Controlled by the `LOG_LEVEL`
environment variable (default: `INFO`). Priorities are stored in an associative array
(`LOG_PRIORITIES`); messages below the current level are silently dropped.

Output goes to two sinks simultaneously:
- **stderr** — colourised with ANSI codes (only when stderr is a TTY).
- **log file** — plain text, one line per message, `YYYY-MM-DD HH:MM:SS [LEVEL] message` format.

Additional visual helpers:
- `log_banner` — ASCII art header printed at startup.
- `log_section` / `log_phase_change` — mark section entry and record per-phase wall-clock duration
  via `LOG_PHASE_START` associative array.
- `log_summary` — prints snapshot ID, duration, hostname, and finish time.

### `lib/utils.sh` — Generic Helpers

Stateless utility functions used across all modules:

| Function | Purpose |
|---|---|
| `command_exists` | Tests whether a binary is on `$PATH` |
| `require_command` | `command_exists` + fatal log on failure |
| `require_root` | Asserts `EUID == 0` |
| `require_file` | Asserts a file path exists |
| `require_directory` | Asserts a directory path exists |
| `create_directory` | `mkdir -p` with error logging |
| `is_dry_run` | Returns true when `DRY_RUN=true` |
| `run` | Logs a command in dry-run mode; otherwise executes it |

### `lib/drive.sh` — Drive Validation

Manages the external backup drive lifecycle:

1. `drive_is_mounted` — checks `mountpoint -q`.
2. `drive_mount` — resolves the device by UUID via `blkid`, then calls `mount`.
3. `drive_check_writable` — writes and removes a test file.
4. `drive_check_space` — reads `df -k` and enforces a 5 GB minimum.
5. `drive_validate` — composes the above into the single call used by `backup.sh`.

### `lib/nextcloud.sh` — OCC Wrapper and Maintenance Mode

Wraps `nextcloud.occ` calls through `nextcloud_occ()`, which captures stdout/stderr, enforces a
configurable timeout (`NEXTCLOUD_OCC_TIMEOUT`, default 30 s), and maps exit codes to log entries.

Public API:
- `nextcloud_check` — verifies the `occ` binary is executable.
- `nextcloud_maintenance_enable` / `nextcloud_maintenance_disable` — toggle maintenance mode; both
  are no-ops in dry-run mode.
- `nextcloud_is_maintenance` — queries current mode.
- `nextcloud_status`, `nextcloud_version`, `nextcloud_data_directory` — informational OCC calls.
- `nextcloud_cleanup` — EXIT trap handler; disables maintenance mode if still active.

### `lib/nextcloud_backup.sh` — Snap DB Export

Uses `nextcloud.export -b` (the Snap-native export tool) to produce a database backup in
`/var/snap/nextcloud/common/backups/`. Detects the newly created export file by diffing the
directory listing before and after the export command. Exposes the path via
`NEW_NEXTCLOUD_DB_EXPORT`.

`nextcloud_cleanup_exports` removes all files from `NEXTCLOUD_BACKUP_DIR` after Restic has
snapshotted them.

### `lib/database.sh` — Direct MySQL Dump

Alternative/supplementary database dump that reads connection details directly from Nextcloud's
config via `nextcloud.occ config:system:get`. Calls `/snap/nextcloud/current/bin/mysqldump` with
`--single-transaction --quick --lock-tables=false` and compresses output with `gzip`. Handles both
TCP hosts and Unix socket connections. The dump path is exported as `DATABASE_LAST_DUMP`.

> Note: `backup.sh` currently uses `nextcloud_backup.sh` (Snap export) rather than `database.sh`
> directly. `database.sh` is available for future use or direct dump scenarios.

### `lib/restic.sh` — Restic Backup and Retention

Thin wrapper around the `restic` CLI:

| Function | Action |
|---|---|
| `restic_init` | Exports `RESTIC_REPOSITORY` and `RESTIC_PASSWORD_FILE` into the environment |
| `restic_check` | Verifies `restic` is on `$PATH` and the password file exists |
| `restic_repository_exists` | Probes with `restic snapshots` |
| `restic_repository_init` | Runs `restic init` if repo doesn't exist yet |
| `restic_unlock` | Removes stale locks |
| `restic_backup` | Streams and formats JSON progress from `restic backup`, tagged and grouped by `host,tags`; adds `--dry-run` when `DRY_RUN=true` |
| `restic_retention` | Streams `restic forget --prune` progress for the configured tag, grouped by `host,tags`, with daily/weekly/monthly keep counts |
| `restic_get_latest_snapshot` | Returns the short ID of the most recent snapshot via `jq` |
| `restic_get_stats` | Logs `restic stats latest` |

### `lib/notifier.sh` — Java JAR Notifier

Wraps a Java notifier application at `$NOTIFIER_DIR/home-lab-notifier.jar`. Three public
functions map to three lifecycle events:

| Function | `--event` flag | Extra args |
|---|---|---|
| `notifier_start` | `START` | — |
| `notifier_success` | `SUCCESS` | `--message`, `--snapshot`, `--duration` |
| `notifier_failure` | `FAILURE` | `--message`, optional `--snapshot`, `--duration` |

`_validate` is called inside each function to abort immediately if the JAR is missing.

> `notifier_failure` is defined but not yet wired into the EXIT trap.

---

## Data Flow

```
bin/backup.sh
  └─ common_init()
       ├─ config_init()          ← reads config/backup.conf
       ├─ log_init()             ← creates log/backup-YYYY-MM-DD.log
       ├─ notifier_init()        ← sources notifier.sh
       ├─ nextcloud_backup_init()← creates /var/snap/nextcloud/common/backups/
       └─ restic_init/check()    ← exports RESTIC_REPOSITORY / RESTIC_PASSWORD_FILE

  └─ notifier_start()            ← sends START event to JAR

  └─ trap nextcloud_cleanup EXIT

  └─ nextcloud_check()           ← verifies nextcloud.occ is executable
  └─ drive_validate()            ← mount → writable → space check

  └─ nextcloud_maintenance_enable()

  └─ nextcloud_backup_db()       ← nextcloud.export -b → NEW_NEXTCLOUD_DB_EXPORT

  └─ restic_repository_init()    ← restic init (if needed)
  └─ restic_unlock()
  └─ restic_backup(DATA CONFIG EXPORT)   ← restic backup

  └─ restic_retention()          ← restic forget --prune

  └─ nextcloud_cleanup_exports() ← rm -rf /var/snap/nextcloud/common/backups/*

  └─ nextcloud_maintenance_disable()

  └─ notifier_success()          ← sends SUCCESS + snapshot ID + duration
  └─ log_summary()
  └─ restic_get_stats()
```

---

## Environment Variables

Variables used across modules. All are set by `config/backup.conf` unless noted.

| Variable | Source | Purpose |
|---|---|---|
| `BACKUP_BASE_DIR` | `bin/backup.sh` | Project root; all other paths derived from this |
| `BACKUP_MOUNT` | `backup.conf` | Mount point of the external backup drive |
| `BACKUP_DEVICE_UUID` | `backup.conf` | blkid UUID used to locate the drive |
| `RESTIC_REPOSITORY` | `backup.conf` | Path to the Restic repository |
| `RESTIC_PASSWORD_FILE` | `backup.conf` | Path to the Restic password file |
| `RESTIC_TAG` | `backup.conf` | Non-blank tag identifying the Nextcloud backup set |
| `RESTIC_PROGRESS_FPS` | environment / `backup.conf` | Positive Restic progress frequency; environment takes precedence |
| `RESTIC_CACHE_DIR` | `backup.conf` | Restic cache directory |
| `RESTIC_RETENTION_DAILY` | `backup.conf` | Daily snapshots to keep |
| `RESTIC_RETENTION_WEEKLY` | `backup.conf` | Weekly snapshots to keep |
| `RESTIC_RETENTION_MONTHLY` | `backup.conf` | Monthly snapshots to keep |
| `NEXTCLOUD_DATA_DIR` | `backup.conf` | Nextcloud data directory |
| `NEXTCLOUD_CONFIG_DIR` | `backup.conf` | Nextcloud config directory |
| `NEXTCLOUD_OCC` | `backup.conf` / default | Path to `nextcloud.occ` binary |
| `NEXTCLOUD_OCC_TIMEOUT` | `backup.conf` / default | Timeout in seconds for OCC calls |
| `NOTIFIER_DIR` | `backup.conf` | Directory containing `home-lab-notifier.jar` |
| `LOG_LEVEL` | `backup.conf` / env | Minimum log level (`DEBUG`/`INFO`/`WARN`/`ERROR`) |
| `DRY_RUN` | env override | Set to `true` to skip all writes |
| `NEW_NEXTCLOUD_DB_EXPORT` | `nextcloud_backup.sh` | Path to the newly created Snap export file |
| `DATABASE_LAST_DUMP` | `database.sh` | Path to the direct mysqldump output file |
