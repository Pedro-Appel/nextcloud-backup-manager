# Java Migration Spec — nextcloud-backup-manager

## Goal

Rewrite the Bash backup service as a plain Java CLI application. No web framework. The binary is
invoked directly (cron or systemd timer) and exits when the backup workflow completes. Behaviour,
configuration format, and external integrations must stay identical to the Bash version.

---

## Non-Goals

- No embedded scheduler (Quartz, Spring Scheduler, etc.) — scheduling stays external.
- No HTTP server or REST API.
- No dependency injection framework (Spring Boot, Quarkus, Micronaut).
- No change to `config/backup.conf` format or key names.
- No change to the external Restic, nextcloud.occ, or notifier JAR interfaces.

---

## Tech Stack

| Concern | Choice | Reason |
|---|---|---|
| Language | Java 21 | LTS, records, sealed classes, pattern matching available |
| Build | Gradle 8 (Kotlin DSL) | Learning goal; Kotlin DSL is the current Gradle standard |
| Logging | SLF4J 2 + Logback | Same dual-sink (console + file) behaviour as the Bash logger |
| Process exec | `ProcessBuilder` (stdlib) | No extra dependency; wraps all subprocess calls |
| Config | `java.util.Properties` | `backup.conf` is already a valid properties file |
| Packaging | Shadow (fat JAR) | Single deployable artifact, no classpath setup on server |
| Testing | JUnit 5 + Mockito | Standard; allows mocking `ProcessBuilder` and file I/O |

---

## Project Layout

```
nextcloud-backup-manager/
├── bin/
│   └── backup.sh                   # Kept — legacy fallback, not modified
├── lib/                            # Kept — legacy fallback, not modified
├── config/
│   └── backup.conf                 # Shared by both Bash and Java versions
├── src/
│   ├── main/
│   │   └── java/dev/irattiz/backup/
│   │       ├── BackupApplication.java          # Entry point: main(), orchestration
│   │       ├── context/
│   │       │   └── BackupContext.java          # Wires config + services together
│   │       ├── config/
│   │       │   └── BackupConfig.java           # Loads backup.conf, validates, exposes values
│   │       ├── logging/
│   │       │   └── BackupLogger.java           # Logback config: level, file sink, console sink
│   │       ├── util/
│   │       │   └── ShellCommand.java           # ProcessBuilder wrapper: run(), runCapture(), dryRun()
│   │       └── service/
│   │           ├── DriveService.java           # Mount, writable check, space check
│   │           ├── NextcloudService.java        # OCC wrapper, maintenance mode
│   │           ├── NextcloudBackupService.java  # nextcloud.export -b, cleanup exports
│   │           ├── DatabaseService.java         # Direct mysqldump (supplementary)
│   │           ├── ResticService.java           # init, unlock, backup, retention, stats
│   │           └── NotifierService.java         # START / SUCCESS / FAILURE events
│   └── test/
│       └── java/dev/irattiz/backup/
│           ├── config/
│           │   └── BackupConfigTest.java
│           ├── service/
│           │   ├── DriveServiceTest.java
│           │   ├── NextcloudServiceTest.java
│           │   ├── ResticServiceTest.java
│           │   └── NotifierServiceTest.java
│           └── util/
│               └── ShellCommandTest.java
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── SPEC.md                         # This file
├── ARCHITECTURE.md
├── CONVENTIONS.md
└── README.md
```

---

## Module Responsibilities

### `BackupApplication` — Entry Point

- Parses CLI args (none required; `--dry-run` overrides `DRY_RUN` env var).
- Instantiates `BackupContext`.
- Calls each phase in the same order as `bin/backup.sh main()`.
- Registers a JVM shutdown hook as the equivalent of `trap nextcloud_cleanup EXIT`.
- Returns exit code 0 on success, 1 on any unhandled failure.

### `BackupContext` — Wiring

- Holds a single `BackupConfig` instance.
- Constructs all service instances (no DI framework — plain constructor injection).
- Exposes getters so `BackupApplication` can call service methods without knowing their
  dependencies.

### `BackupConfig` — Configuration

- Loads `config/backup.conf` using `java.util.Properties`.
- Validates required keys on construction (fail-fast).
- Enforces absolute paths for all path-type keys.
- Exposes typed getters (`getResticRetentionDaily()` returns `int`, path getters return `Path`).
- Auto-detects Snap defaults for `NEXTCLOUD_DATA_DIR` when not explicitly set.
- Supports `DRY_RUN` from both the config file and system environment (`System.getenv` wins).

### `ShellCommand` — Process Execution

- Wraps `ProcessBuilder`.
- `run(String... args)` — executes a command, streams stdout/stderr to the logger, throws
  `BackupException` on non-zero exit.
- `runCapture(String... args)` — same but returns stdout as a `String`.
- `withTimeout(Duration)` — applies a wall-clock timeout; throws on breach.
- In dry-run mode, logs `[DRY-RUN] Would run: <command>` and returns without executing.
- All commands are logged at DEBUG level before execution.

### `DriveService`

Maps directly to `lib/drive.sh`:

- `isMounted()` — checks `/proc/mounts` or runs `mountpoint -q`.
- `mount()` — resolves device by UUID, runs `mount`.
- `checkWritable()` — creates and removes a sentinel file.
- `checkSpace(long minBytes)` — reads `df -k`, enforces minimum.
- `validate()` — composes the above; called from `BackupApplication`.

### `NextcloudService`

Maps directly to `lib/nextcloud.sh`:

- `check()` — verifies `nextcloud.occ` is executable.
- `maintenanceEnable()` / `maintenanceDisable()` — toggle via `occ`.
- `isMaintenance()` — queries current mode.
- `cleanup()` — disables maintenance mode if active; called from the shutdown hook.
- OCC calls go through `ShellCommand.withTimeout(NEXTCLOUD_OCC_TIMEOUT)`.

### `NextcloudBackupService`

Maps to `lib/nextcloud_backup.sh`:

- `exportDb()` — runs `nextcloud.export -b`, detects the new export file by diffing the
  directory before/after, stores path in an instance field.
- `cleanupExports()` — deletes all files from `NEXTCLOUD_BACKUP_DIR`.
- `getExportPath()` — returns the `Path` of the last export.

### `DatabaseService`

Maps to `lib/database.sh` (supplementary, not called by default workflow):

- `dump()` — reads DB credentials from `occ config:system:get`, calls Snap mysqldump, gzips
  output.
- `getLastDumpPath()` — returns path of the most recent dump.

### `ResticService`

Maps to `lib/restic.sh`:

- `init()` — exports env vars, checks `restic` is on PATH and password file exists.
- `repositoryExists()` — probes with `restic snapshots`.
- `repositoryInit()` — runs `restic init` if needed.
- `unlock()` — removes stale locks.
- `backup(List<Path> paths)` — runs `restic backup`; adds `--dry-run` when dry-run mode is on.
- `applyRetention()` — runs `restic forget --prune` with configured keep counts.
- `getLatestSnapshotId()` — parses `restic snapshots --json` output to get the most recent ID.
- `getStats()` — runs `restic stats latest`, logs output.

### `NotifierService`

Maps to `lib/notifier.sh`:

- `sendStart()` — fires `--event START`.
- `sendSuccess(String snapshotId, Duration duration)` — fires `--event SUCCESS`.
- `sendFailure(String message, String snapshotId, Duration duration)` — fires `--event FAILURE`.
- Validates JAR existence before each call; throws `BackupException` if missing.
- In dry-run mode, logs the would-be notification and returns.

---

## Backup Workflow (mirrors `bin/backup.sh`)

```
BackupApplication.main()
  └─ BackupContext()             ← loads config, constructs services
  └─ notifier.sendStart()
  └─ register shutdown hook      ← nextcloud.cleanup() equivalent
  └─ nextcloud.check()
  └─ drive.validate()
  └─ nextcloud.maintenanceEnable()
  └─ nextcloudBackup.exportDb()
  └─ restic.repositoryInit()
  └─ restic.unlock()
  └─ restic.backup([data, config, export])
  └─ restic.applyRetention()
  └─ nextcloudBackup.cleanupExports()
  └─ nextcloud.maintenanceDisable()
  └─ notifier.sendSuccess(snapshotId, duration)
  └─ restic.getStats()
```

---

## Error Handling

- A single checked exception `BackupException` propagates up to `BackupApplication.main()`.
- `main()` catches it, calls `notifier.sendFailure(...)`, and exits with code 1.
- The shutdown hook fires regardless, ensuring maintenance mode is always disabled.
- No silent failures — every caught exception is logged before re-throwing or handling.

---

## Logging

Logback is configured programmatically (or via `logback.xml` on the classpath):

- **Console appender** — colourised via Logback's `%highlight` pattern, writing to stderr.
- **File appender** — plain text, daily rolling, written to `<project_root>/log/backup-YYYY-MM-DD.log`.
- Log level controlled by `LOG_LEVEL` from config (mapped to SLF4J levels).
- Phase timing is implemented with `Instant.now()` at section start/end, logged as `[PHASE] <name> completed in <ms>ms`.

---

## Dry-Run Mode

Dry-run is activated by setting `DRY_RUN=true` in environment or config. When active:

- `ShellCommand.run()` and `runCapture()` log and skip execution.
- `NextcloudService.maintenanceEnable/Disable()` are no-ops.
- `ResticService.backup()` passes `--dry-run` to the CLI (same as Bash version).
- `NotifierService` logs the would-be call and returns.
- `DriveService.mount()` and write checks are skipped.

---

## Configuration

`config/backup.conf` is unchanged. `BackupConfig` reads it with `Properties.load()`.
The file is located at `<project_root>/config/backup.conf` where project root is derived from
the JAR location at runtime (same logic as Bash's `BACKUP_BASE_DIR`).

---

## Packaging and Deployment

- The Shadow plugin produces `build/libs/nextcloud-backup-manager-<version>-all.jar`.
- Deployed to the server as a single file.
- Invoked via: `sudo java -jar /opt/backup/nextcloud-backup-manager-all.jar`
- Or via systemd service unit (future enhancement).
- `DRY_RUN=true java -jar ...` for rehearsals.

---

## Testing Strategy

- Unit tests mock `ShellCommand` to avoid real subprocess calls.
- `BackupConfigTest` exercises validation logic against a temp properties file.
- Integration tests (tagged `@Tag("integration")`) are excluded from the default Gradle `test`
  task and run explicitly with `./gradlew integrationTest`.
- No test hits real `restic`, `nextcloud.occ`, or the notifier JAR.

---

## Phased Development Plan

### Phase 1 — Scaffold
- Gradle project init (Kotlin DSL), Shadow plugin, Java 21 toolchain.
- `BackupConfig` + `BackupConfigTest`.
- `ShellCommand` + `ShellCommandTest`.
- Logging setup (Logback config, `BackupLogger` wrapper).
- `BackupContext` skeleton.

### Phase 2 — Core Services
- `DriveService` + test.
- `NextcloudService` + test.
- `NextcloudBackupService` + test.
- `ResticService` + test.

### Phase 3 — Notifier and Orchestration
- `NotifierService` + test.
- `BackupApplication` — full workflow wired up.
- Shutdown hook.
- End-to-end dry-run smoke test.

### Phase 4 — Polish
- `DatabaseService` (supplementary).
- Log formatting parity with Bash version (banner, section headers, summary).
- `--dry-run` CLI flag support.
- README update for Java build/run instructions.
- Systemd service unit file.
