# Implementation Plan — nextcloud-backup-manager Java Migration

## Overview

This plan converts the Bash backup service into a plain Java 21 CLI application built with Gradle
(Kotlin DSL). It follows the specification in `SPEC.md`. Development happens on the
`feature/java-migration` branch. The existing Bash scripts in `bin/` and `lib/` are untouched
throughout.

Each task produces a working, demonstrable increment. Tasks build strictly on top of prior tasks —
no orphaned code is introduced.

---

## Conventions for This Plan

- **Acceptance tests** are automated JUnit 5 tests that must pass before the task is considered
  complete. Run them with `./gradlew test`.
- **Expected results** describe the observable, human-verifiable outcome after the task is done.
- **Demo** describes the exact command to run and the output or behaviour to confirm.
- Tasks within a phase are ordered by dependency — complete them top-to-bottom.

---

## Phase 1 — Scaffold

Goal: establish a compiling, testable project with the two cross-cutting foundations
(`BackupConfig` and `ShellCommand`) that every service will depend on.

---

### Task 1: Gradle project initialisation ✅

**Objective**
Create a minimal but complete Gradle 8 Kotlin DSL project that compiles Java 21 source and
produces a fat JAR via the Shadow plugin. No application logic yet.

**Implementation guidance**
- Create `settings.gradle.kts` with `rootProject.name = "nextcloud-backup-manager"`.
- Create `build.gradle.kts` with:
  - `plugins`: `java`, `application`, `com.github.johnrengelman.shadow` (version `8.1.1`).
  - `java.toolchain.languageVersion = JavaLanguageVersion.of(21)`.
  - `application.mainClass = "dev.irattiz.backup.BackupApplication"`.
  - `dependencies` block: SLF4J API `2.0.x`, Logback Classic `1.5.x`, JUnit 5 `5.10.x`,
    Mockito Core `5.x`, pinned to exact versions.
  - `tasks.test { useJUnitPlatform() }`.
  - Shadow `shadowJar` configured to append `-all` classifier.
- Generate the Gradle wrapper with `gradle wrapper --gradle-version 8.8`.
- Create the source tree skeleton: `src/main/java/dev/irattiz/backup/` and
  `src/test/java/dev/irattiz/backup/`.
- Create a placeholder `BackupApplication.java` with an empty `main()` so the project compiles.
- Create `src/main/resources/logback.xml` with a minimal console appender so Logback does not
  print warnings on startup.

**Acceptance tests**
- No automated test yet — the build itself is the acceptance gate.

**Expected results**
- `./gradlew build` exits 0 with no errors or warnings.
- `./gradlew shadowJar` produces `build/libs/nextcloud-backup-manager-1.0.0-all.jar`.
- `java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar` runs and exits 0 without
  printing anything.

**Demo**
```bash
./gradlew shadowJar
java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
echo "Exit code: $?"   # must print 0
```

---

### Task 2: `BackupException` ✅

**Objective**
Introduce the single checked exception used throughout the application before any class that
throws it is written.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/exception/BackupException.java`.
- Extends `Exception`.
- Two constructors: `BackupException(String message)` and
  `BackupException(String message, Throwable cause)`.
- No other logic.

**Acceptance tests**
- `BackupExceptionTest`: assert that `new BackupException("msg")` has the correct message and
  that `new BackupException("msg", cause)` wraps the cause correctly.

**Expected results**
- `./gradlew test` passes with 2 tests green.

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.exception.BackupExceptionTest"
# BUILD SUCCESSFUL, 2 tests passed
```

---

### Task 3: `BackupConfig` ✅

**Objective**
Load, validate, and expose `config/backup.conf` as a typed Java object. This is the data
foundation that every service reads.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/config/BackupConfig.java`.
- Constructor `BackupConfig(Path configFile)` loads the file with `Properties.load()`, then calls
  `validate()`.
- `validate()` enforces:
  - Required string keys: `BACKUP_MOUNT`, `BACKUP_DEVICE_UUID`, `RESTIC_REPOSITORY`,
    `RESTIC_PASSWORD_FILE`, `NEXTCLOUD_OCC`, `NOTIFIER_DIR`.
  - All keys whose names end in `_DIR`, `_FILE`, `_REPOSITORY`, or `_MOUNT` must start with `/`
    (absolute path check).
  - `RESTIC_RETENTION_DAILY`, `RESTIC_RETENTION_WEEKLY`, `RESTIC_RETENTION_MONTHLY` must be
    parseable as positive integers.
  - Throws `BackupException` on any violation with a descriptive message.
- Auto-detect Snap defaults: if `NEXTCLOUD_DATA_DIR` is absent, set it to
  `/var/snap/nextcloud/current/nextcloud/data`. Same for `NEXTCLOUD_CONFIG_DIR` and
  `NEXTCLOUD_BACKUP_DIR`.
- `DRY_RUN`: check `System.getenv("DRY_RUN")` first; fall back to the value in the config file;
  default to `false`.
- Expose typed getters:
  - `Path` for all path-type keys.
  - `int` for retention counts.
  - `boolean isDryRun()`.
  - `Duration getOccTimeout()` (parses `NEXTCLOUD_OCC_TIMEOUT` seconds, default 30).
  - `Level getLogLevel()` — maps the string value to `ch.qos.logback.classic.Level`.

**Acceptance tests** (`BackupConfigTest`)
1. `loadsValidConfig` — write a valid temp `backup.conf`, construct `BackupConfig`, assert all
   getters return expected values.
2. `throwsOnMissingRequiredKey` — omit `RESTIC_REPOSITORY`, assert `BackupException` is thrown
   with a message containing `"RESTIC_REPOSITORY"`.
3. `throwsOnRelativePath` — set `BACKUP_MOUNT=relative/path`, assert `BackupException` thrown
   with `"absolute"` in the message.
4. `throwsOnNonIntegerRetention` — set `RESTIC_RETENTION_DAILY=abc`, assert `BackupException`.
5. `envVarOverridesDryRun` — test that when env `DRY_RUN=true` is present `isDryRun()` returns
   `true` regardless of file value. (Refactor `BackupConfig` to accept an env-supplier lambda
   for testability.)
6. `autoDetectsSnapDefaults` — omit `NEXTCLOUD_DATA_DIR`, assert getter returns the Snap default
   path.
7. `occTimeoutDefaultsTo30s` — omit `NEXTCLOUD_OCC_TIMEOUT`, assert `getOccTimeout()` returns
   `Duration.ofSeconds(30)`.

**Expected results**
- `./gradlew test` passes with 7 tests green.

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.config.BackupConfigTest"
# BUILD SUCCESSFUL, 7 tests passed. Cumulative: 9
```

---

### Task 4: Logging setup ✅

**Objective**
Configure Logback with a console (colourised) appender and a daily rolling file appender.
Expose a `BackupLogger` façade so the rest of the codebase never imports Logback directly.

**Implementation guidance**
- Replace the placeholder `logback.xml` in `src/main/resources/` with a full configuration:
  - `ConsoleAppender` writing to `System.err` with pattern
    `%d{HH:mm:ss} %highlight(%-5level) %msg%n`.
  - `RollingFileAppender` with `TimeBasedRollingPolicy` pattern
    `${LOG_DIR}/backup-%d{yyyy-MM-dd}.log` and plain pattern
    `%d{yyyy-MM-dd HH:mm:ss} [%-5level] %msg%n`.
  - Root logger level driven by the `LOG_LEVEL` system property (default `INFO`).
- Create `src/main/java/dev/irattiz/backup/logging/BackupLogger.java`:
  - Static factory `getLogger(Class<?>)` returns an SLF4J `Logger`.
  - Static `configure(Level level, Path logDir)` — sets the root logger level at runtime and
    injects `logDir` into the `LOG_DIR` Logback property.
  - Static helpers `logSection(String name)` and `logPhaseEnd(String name, Instant startedAt)`:
    - `logSection` logs `"=== <name> ==="` at INFO.
    - `logPhaseEnd` logs `"[PHASE] <name> completed in <ms>ms"` at INFO.

**Acceptance tests** (`BackupLoggerTest`)
1. `logSectionFormatsCorrectly` — capture log output, call `logSection("Restic backup")`, assert
   the output contains `"=== Restic backup ==="`.
2. `logPhaseEndIncludesDuration` — call `logPhaseEnd("Drive validation", Instant.now().minusMillis(250))`,
   assert output contains `"[PHASE] Drive validation completed in"` and a number of milliseconds.
3. `configureChangesLevel` — call `BackupLogger.configure(Level.DEBUG, tempDir)`, get a logger,
   assert `logger.isDebugEnabled()` is `true`.

**Expected results**
- `./gradlew test` passes with 3 new tests (12 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.logging.BackupLoggerTest"
# BUILD SUCCESSFUL. Cumulative: 12
```

---

### Task 5: `ShellCommand` ✅

**Objective**
Provide a reusable, testable wrapper around `ProcessBuilder` that all services use for every
external command. This is the most critical utility class — no service should ever instantiate
`ProcessBuilder` directly.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/util/ShellCommand.java`.
- Constructor: `ShellCommand(boolean dryRun)`.
- `void run(String... args) throws BackupException`:
  - In dry-run: logs `[DRY-RUN] Would run: <command>` at INFO and returns.
  - Otherwise: builds a `ProcessBuilder`, inherits environment, redirects stderr to stdout,
    streams stdout lines to `log.debug(...)`, waits for exit, throws `BackupException` if
    exit code != 0 with message `"Command failed (exit <code>): <command>"`.
- `String runCapture(String... args) throws BackupException`:
  - Same as `run` but collects stdout into a `String` and returns it.
  - In dry-run: logs and returns `""`.
- `ShellCommand withTimeout(Duration timeout)`:
  - Returns a new `ShellCommand` that applies `process.waitFor(timeout)`;
    if timed out, destroys the process and throws
    `BackupException("Command timed out: <command>")`.
- All real executions log the full command at DEBUG before launching.

**Acceptance tests** (`ShellCommandTest`)
1. `runExecutesCommand` — run `["echo", "hello"]`, assert no exception is thrown.
2. `runThrowsOnNonZeroExit` — run `["false"]` (always exits 1), assert `BackupException` thrown
   with `"exit 1"` in the message.
3. `runCaptureReturnsStdout` — run `["echo", "captured"]`, assert returned string equals
   `"captured"`.
4. `dryRunSkipsExecution` — construct with `dryRun=true`, run `["false"]`, assert no exception
   and that `"DRY-RUN"` appears in captured log output.
5. `withTimeoutThrowsOnBreach` — run `["sleep", "10"]` with a 100ms timeout, assert
   `BackupException` thrown with `"timed out"` in the message.
6. `commandIsLoggedAtDebug` — run any command, assert the full command string appears in DEBUG
   log output.

**Expected results**
- `./gradlew test` passes with 6 new tests (18 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.util.ShellCommandTest"
# BUILD SUCCESSFUL, 6 tests passed. Cumulative: 18
```

---

### Task 6: `BackupContext` skeleton ✅

**Objective**
Wire `BackupConfig`, `BackupLogger`, and `ShellCommand` into a single context object that
`BackupApplication` and future service tests can use. Services are not yet added — they will be
injected in Phase 2.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/context/BackupContext.java`.
- Constructor `BackupContext(Path projectRoot) throws BackupException`:
  - Resolves `projectRoot.resolve("config/backup.conf")` and constructs `BackupConfig`.
  - Calls `BackupLogger.configure(config.getLogLevel(), projectRoot.resolve("log"))`.
  - Constructs `ShellCommand(config.isDryRun())` and stores it.
- Expose `getConfig()`, `getShell()`, `projectRoot()`.
- Update `BackupApplication.main()`:
  - Derive project root from the JAR location:
    `BackupApplication.class.getProtectionDomain().getCodeSource().getLocation()` → parent of
    parent (accounts for `build/libs/`).
  - Construct `BackupContext(projectRoot)`.
  - Log `"Backup context initialised"` at INFO and exit 0.

**Acceptance tests** (`BackupContextTest`)
1. `initialisesWithValidConfig` — create a temp project root with a valid `config/backup.conf`,
   construct `BackupContext`, assert `getConfig()` and `getShell()` are non-null.
2. `throwsWhenConfigMissing` — point to a directory with no `config/backup.conf`, assert
   `BackupException` is thrown.

**Expected results**
- `./gradlew test` passes with 2 new tests (20 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.context.BackupContextTest"
# BUILD SUCCESSFUL. Cumulative: 20
```

---

## Phase 2 — Core Services

Goal: implement each service class as a thin, testable wrapper over external commands. All tests
mock `ShellCommand` so no real system calls are made.

---

### Task 7: `DriveService` ✅

**Objective**
Implement drive mount validation: mount by UUID, writable check, space check. Maps 1-to-1 with
`lib/drive.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/DriveService.java`.
- Constructor: `DriveService(BackupConfig config, ShellCommand shell)`.
- `boolean isMounted()` — runs `["mountpoint", "-q", config.getBackupMount().toString()]`;
  returns `true` if exit code 0, `false` if exit code 1 (use a try-catch on `BackupException`).
- `void mount() throws BackupException` — resolves device path by running
  `["blkid", "-U", config.getBackupDeviceUuid()]` via `runCapture`, then runs
  `["mount", device.strip(), config.getBackupMount().toString()]`.
- `void checkWritable() throws BackupException` — writes and deletes a sentinel file at
  `config.getBackupMount().resolve(".backup-writable-check")` using `Files.createFile` /
  `Files.delete`; throws `BackupException` if either fails.
- `void checkSpace() throws BackupException` — runs `["df", "-k",
  config.getBackupMount().toString()]` via `runCapture`, parses the available-KB column from
  the second line, multiplies by 1024; throws if below 5 GB (5_368_709_120 bytes).
- `void validate() throws BackupException` — calls `isMounted()` → `mount()` if needed →
  `checkWritable()` → `checkSpace()`.

**Acceptance tests** (`DriveServiceTest`)
1. `validateMountsDriveWhenNotMounted` — mock `isMounted()` to return `false`; verify `mount()`
   is called.
2. `validateSkipsMountWhenAlreadyMounted` — mock `isMounted()` to return `true`; verify
   `mount()` is NOT called.
3. `checkSpaceThrowsWhenBelowMinimum` — mock `runCapture` to return a `df -k` output with
   1_000_000 KB available (~1 GB); assert `BackupException` thrown with `"space"` in message.
4. `checkSpacePassesWhenSufficient` — mock `runCapture` to return 10_000_000 KB; assert no
   exception.
5. `mountUsesBlkidOutput` — mock `runCapture` for `blkid` to return `/dev/sdb1`; verify `run`
   is called with args containing `/dev/sdb1`.
6. `checkWritableThrowsOnReadOnlyFs` — mock `Files` operations to throw `IOException`; assert
   `BackupException`.

**Expected results**
- `./gradlew test` passes with 6 new tests (26 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.DriveServiceTest"
# BUILD SUCCESSFUL. Cumulative: 26
```

---

### Task 8: `NextcloudService` ✅

**Objective**
Wrap `nextcloud.occ` calls with timeout support and implement maintenance mode toggling and the
shutdown-hook cleanup method. Maps to `lib/nextcloud.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/NextcloudService.java`.
- Constructor: `NextcloudService(BackupConfig config, ShellCommand shell)`.
  - Internally holds `occShell = shell.withTimeout(config.getOccTimeout())`.
- `void check() throws BackupException` — verifies `config.getNextcloudOcc()` is executable
  via `Files.isExecutable(path)`; throws `BackupException` if not.
- `void maintenanceEnable() throws BackupException` — runs
  `[occ, "maintenance:mode", "--on"]` via `occShell`.
- `void maintenanceDisable() throws BackupException` — runs
  `[occ, "maintenance:mode", "--off"]`.
- `boolean isMaintenance() throws BackupException` — runs `[occ, "maintenance:mode"]` via
  `runCapture`; returns `true` if output contains `"enabled"`.
- `void cleanup()` — calls `isMaintenance()` in a try-catch; if active calls
  `maintenanceDisable()`; logs any caught exception as WARN but does not re-throw.

**Acceptance tests** (`NextcloudServiceTest`)
1. `checkThrowsWhenOccNotExecutable`
2. `checkPassesWhenOccExists`
3. `maintenanceEnableRunsCorrectCommand`
4. `maintenanceDisableRunsCorrectCommand`
5. `isMaintenanceReturnsTrueWhenEnabled`
6. `isMaintenanceReturnsFalseWhenDisabled`
7. `cleanupDisablesMaintenanceWhenActive`
8. `cleanupDoesNotThrowOnError`

**Expected results**
- `./gradlew test` passes with 8 new tests (34 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.NextcloudServiceTest"
# BUILD SUCCESSFUL. Cumulative: 34
```

---

### Task 9: `NextcloudBackupService` ✅

**Objective**
Implement the Snap-native database export and post-backup export cleanup. Maps to
`lib/nextcloud_backup.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/NextcloudBackupService.java`.
- Constructor: `NextcloudBackupService(BackupConfig config, ShellCommand shell)`.
- `void init() throws BackupException` — creates `config.getNextcloudBackupDir()` using
  `Files.createDirectories`.
- `void exportDb() throws BackupException`:
  - Snapshots the directory listing of `NEXTCLOUD_BACKUP_DIR` before export.
  - Runs `["nextcloud.export", "-b"]` via `shell` with a 10-minute timeout (constant).
  - Diffs the directory listing after; stores the new file in `exportPath`.
  - Throws `BackupException` if no new file is detected.
- `Path getExportPath()` — throws `IllegalStateException` if `exportDb()` has not been called.
- `void cleanupExports() throws BackupException` — deletes all regular files inside
  `NEXTCLOUD_BACKUP_DIR` using `Files.walk`; logs each deleted file at DEBUG.

**Acceptance tests** (`NextcloudBackupServiceTest`)
1. `initCreatesBackupDirectory`
2. `exportDbDetectsNewFile`
3. `exportDbThrowsWhenNoNewFileDetected`
4. `getExportPathThrowsBeforeExport`
5. `cleanupExportsDeletesAllFiles`

**Expected results**
- `./gradlew test` passes with 5 new tests (39 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.NextcloudBackupServiceTest"
# BUILD SUCCESSFUL. Cumulative: 39
```

---

### Task 10: `ResticService` ✅

**Objective**
Implement all Restic operations: repository init, unlock, backup, retention, snapshot ID
extraction, and stats. Maps to `lib/restic.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/ResticService.java`.
- Constructor: `ResticService(BackupConfig config, ShellCommand shell)`.
- `void init() throws BackupException` — verifies `restic` is on PATH via
  `runCapture("which", "restic")`; verifies password file exists with `Files.exists`; throws
  `BackupException` for either failure.
- `boolean repositoryExists() throws BackupException` — runs `["restic", "snapshots"]`; returns
  `true` if exits 0, `false` if exits non-zero.
- `void repositoryInit() throws BackupException` — runs `["restic", "init"]` if
  `!repositoryExists()`.
- `void unlock() throws BackupException` — runs `["restic", "unlock"]`.
- `void backup(List<Path> paths) throws BackupException` — builds args
  `["restic", "backup", ...paths]`; appends `"--dry-run"` when `config.isDryRun()`.
- `void applyRetention() throws BackupException` — runs `["restic", "forget", "--prune",
  "--keep-daily", daily, "--keep-weekly", weekly, "--keep-monthly", monthly]`.
- `String getLatestSnapshotId() throws BackupException` — runs
  `["restic", "snapshots", "--json", "--last"]` via `runCapture`; parses the JSON array with
  `jakarta.json` (add `jakarta.json:jakarta.json-api` + `org.glassfish:jakarta.json` as
  dependencies) to extract the first element's `"short_id"` field.
- `void getStats() throws BackupException` — runs `["restic", "stats", "latest"]`, logs output
  at INFO.

**Acceptance tests** (`ResticServiceTest`)
1. `initThrowsWhenResticMissing`
2. `initThrowsWhenPasswordFileMissing`
3. `repositoryInitSkipsWhenExists`
4. `repositoryInitRunsWhenMissing`
5. `backupAppendsDryRunFlag`
6. `backupDoesNotAppendDryRunFlagInNormalMode`
7. `applyRetentionPassesCorrectKeepArgs`
8. `getLatestSnapshotIdParsesJson`

**Expected results**
- `./gradlew test` passes with 8 new tests (47 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.ResticServiceTest"
# BUILD SUCCESSFUL. Cumulative: 47
```

---

## Phase 3 — Notifier and Orchestration

Goal: complete the remaining service, wire everything into `BackupApplication`, and demonstrate
an end-to-end dry-run of the full backup workflow without touching any real external system.

---

### Task 11: `NotifierService` ✅

**Objective**
Wrap the existing Java notifier JAR as a subprocess call. Maps to `lib/notifier.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/NotifierService.java`.
- Constructor: `NotifierService(BackupConfig config, ShellCommand shell)`.
- Private `void validate() throws BackupException` — checks
  `config.getNotifierDir().resolve("home-lab-notifier.jar")` exists.
- `void sendStart() throws BackupException` — `["java", "-jar", jarPath, "--event", "START"]`.
  In dry-run: log and return.
- `void sendSuccess(String snapshotId, Duration duration) throws BackupException` — adds
  `"--snapshot"`, `snapshotId`, `"--duration"`, `duration.toSeconds()` args.
- `void sendFailure(String message, String snapshotId, Duration duration) throws BackupException`
  — `"FAILURE"` event; `snapshotId` and `duration` args omitted when null/zero.

**Acceptance tests** (`NotifierServiceTest`)
1. `sendStartThrowsWhenJarMissing`
2. `sendStartRunsCorrectCommand`
3. `sendSuccessIncludesSnapshotAndDuration`
4. `sendFailureOmitsSnapshotWhenNull`
5. `dryRunSkipsSendStart`

**Expected results**
- `./gradlew test` passes with 5 new tests (52 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.NotifierServiceTest"
# BUILD SUCCESSFUL. Cumulative: 52
```

---

### Task 12: Wire services into `BackupContext` ✅ ✅

**Objective**
Add all services to `BackupContext` so `BackupApplication` can retrieve them through a single
object.

**Implementation guidance**
- Update `BackupContext` constructor to instantiate all services in dependency order:
  `DriveService`, `NextcloudService`, `NextcloudBackupService`, `ResticService`,
  `NotifierService`.
- Call `restic.init()` and `nextcloudBackup.init()` in constructor.
- Expose getters: `getDrive()`, `getNextcloud()`, `getNextcloudBackup()`, `getRestic()`,
  `getNotifier()`.

**Acceptance tests** (add to `BackupContextTest`)
3. `exposesAllServices`
4. `throwsWhenResticInitFails`

**Expected results**
- `./gradlew test` passes with 2 new tests (54 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.context.BackupContextTest"
# BUILD SUCCESSFUL. Cumulative: 54
```

---

### Task 13: `BackupApplication` — full orchestration

**Objective**
Implement the complete backup workflow in `BackupApplication.main()`, mirroring the phase
sequence in `bin/backup.sh`.

**Implementation guidance**
- Extract `runWorkflow(BackupContext context)` as a package-private method for testability.
- Phase sequence (each wrapped in `logSection` / `logPhaseEnd`):
  1. `notifier.sendStart()`
  2. Register shutdown hook: `Runtime.getRuntime().addShutdownHook(new Thread(context.getNextcloud()::cleanup))`
  3. `nextcloud.check()`
  4. `drive.validate()`
  5. `nextcloud.maintenanceEnable()`
  6. `nextcloudBackup.exportDb()`
  7. `restic.repositoryInit()`
  8. `restic.unlock()`
  9. `restic.backup([data, config, export])`
  10. `restic.applyRetention()`
  11. `nextcloudBackup.cleanupExports()`
  12. `nextcloud.maintenanceDisable()`
  13. `restic.getLatestSnapshotId()` → `notifier.sendSuccess(snapshotId, duration)`
  14. `restic.getStats()`
- Top-level `catch (BackupException e)`: log error, call `notifier.sendFailure(...)`,
  `System.exit(1)`.

**Acceptance tests** (`BackupApplicationTest`)
1. `fullWorkflowCallsAllPhases` — mock all services; verify call order with Mockito `InOrder`.
2. `failureInDriveValidateSendsFailureNotification`
3. `failureInMaintenanceEnableStillDisablesViaShutdownHook`

**Expected results**
- `./gradlew test` passes with 3 new tests (57 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.BackupApplicationTest"
# BUILD SUCCESSFUL. Cumulative: 57
```

---

### Task 14: End-to-end dry-run smoke test

**Objective**
Run the assembled fat JAR in dry-run mode against a real (local) config and confirm that the
full workflow executes, all phases are logged, no external commands are actually run, and the
process exits 0.

**Implementation guidance**
- Add integration test class `BackupApplicationIT` tagged `@Tag("integration")`.
- Configure Gradle: exclude `integration` tag from `test` task; add `integrationTest` task that
  includes only that tag.
- The test:
  1. Creates a temp project root with a valid `backup.conf` pointing to non-existent but
     syntactically valid paths.
  2. Sets `DRY_RUN=true` via system property injection.
  3. Calls `BackupApplication.runWorkflow(context)` with a real `BackupContext`.
  4. Asserts no exception is thrown.
  5. Asserts log file was created at `<projectRoot>/log/backup-<today>.log`.
  6. Asserts log file contains `"[DRY-RUN]"` at least once.

**Acceptance test:** The integration test itself.

**Expected results**
- `./gradlew integrationTest` exits 0.
- Log file `log/backup-<today>.log` created and contains `[DRY-RUN]` entries.

**Demo**
```bash
./gradlew integrationTest
DRY_RUN=true java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
# All phases logged with [DRY-RUN], exit code 0
```

---

## Phase 4 — Polish

Goal: add the supplementary database service, achieve full log formatting parity with the Bash
version, add the `--dry-run` CLI flag, and produce deployment artefacts.

---

### Task 15: `DatabaseService`

**Objective**
Implement the direct MySQL dump path as a supplementary service (not in the default workflow).
Maps to `lib/database.sh`.

**Implementation guidance**
- Create `src/main/java/dev/irattiz/backup/service/DatabaseService.java`.
- Constructor: `DatabaseService(BackupConfig config, ShellCommand shell)`.
- `void dump() throws BackupException`:
  - Reads `dbHost`, `dbName`, `dbUser`, `dbPassword` via
    `["nextcloud.occ", "config:system:get", <key>]` via `runCapture`.
  - Detects socket vs TCP by checking if `dbHost` starts with `/`.
  - Builds mysqldump command: `/snap/nextcloud/current/bin/mysqldump --single-transaction
    --quick --lock-tables=false`.
  - Pipes output to a gzip file at
    `<projectRoot>/database/<timestamp>-nextcloud.sql.gz` using `GZIPOutputStream`.
  - Stores output path in `lastDumpPath`.
- `Path getLastDumpPath()` — throws `IllegalStateException` if not called yet.

**Acceptance tests** (`DatabaseServiceTest`)
1. `dumpUsesSocketWhenHostIsPath`
2. `dumpUsesTcpWhenHostIsHostname`
3. `dumpStoresOutputPath`
4. `getLastDumpPathThrowsBeforeDump`

**Expected results**
- `./gradlew test` passes with 4 new tests (62 total).

**Demo**
```bash
./gradlew test --tests "dev.irattiz.backup.service.DatabaseServiceTest"
# BUILD SUCCESSFUL. Cumulative: 62
```

---

### Task 16: Log formatting parity

**Objective**
Align the Java log output with the Bash version: startup banner, section headers with timing,
and an end-of-run summary.

**Implementation guidance**
- Add `BackupLogger.logBanner()` — prints the same ASCII art header as `log_banner` in
  `logging.sh`. Hardcode the banner string as a multi-line constant.
- Update `logSection(String name)` to print a horizontal rule above the section name.
- Add `BackupLogger.logSummary(String snapshotId, Duration duration, String hostname)`:
  - Logs snapshot ID, total duration in human-readable form (e.g., `"2m 15s"`), hostname,
    and finish timestamp at INFO.
- Call `logBanner()` at the start of `BackupApplication.main()`.
- Call `logSummary(...)` at the end of the workflow.

**Acceptance tests** (add to `BackupLoggerTest`)
4. `logBannerContainsProjectName`
5. `logSummaryContainsSnapshotId`
6. `logSummaryFormatsDuration` — `Duration.ofSeconds(135)` → `"2m 15s"`

**Expected results**
- `./gradlew test` passes with 3 new tests (65 total).

**Demo**
```bash
DRY_RUN=true java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar
# First lines: ASCII banner
# Last lines: Snapshot / Duration / Host / Finished
```

---

### Task 17: `--dry-run` CLI flag

**Objective**
Allow `--dry-run` to be passed as a command-line argument as an alternative to the `DRY_RUN`
environment variable.

**Implementation guidance**
- Parse `args` for `"--dry-run"` before constructing `BackupContext`.
- If found, `System.setProperty("DRY_RUN", "true")`.
- Update `BackupConfig` to check env → system property → config file.
- Add `--help` flag: prints usage and exits 0:
  ```
  Usage: nextcloud-backup-manager [--dry-run] [--help]
    --dry-run   Simulate all operations without writing data
    --help      Show this message
  ```

**Acceptance tests** (add to `BackupApplicationTest`)
4. `dryRunFlagSetsDryRunMode`
5. `helpFlagPrintsUsageAndExitsZero`

**Expected results**
- `./gradlew test` passes with 2 new tests (67 total).

**Demo**
```bash
java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar --help
java -jar build/libs/nextcloud-backup-manager-1.0.0-all.jar --dry-run
```

---

### Task 18: README update and systemd unit files

**Objective**
Document the Java build and run process and provide a production-ready systemd service unit and
timer.

**Implementation guidance**
- Update `README.md`:
  - Add **Java Build** section: `./gradlew shadowJar`
  - Add **Running (Java)** section with normal, dry-run, and debug invocations.
  - Add **Testing** section: `./gradlew test` and `./gradlew integrationTest`.
  - Keep existing Bash sections intact.
- Create `deploy/nextcloud-backup.service`:
  ```ini
  [Unit]
  Description=Nextcloud Backup Manager
  After=network.target

  [Service]
  Type=oneshot
  User=root
  ExecStart=/usr/bin/java -jar /opt/backup/nextcloud-backup-manager-all.jar
  StandardOutput=journal
  StandardError=journal
  ```
- Create `deploy/nextcloud-backup.timer`:
  ```ini
  [Unit]
  Description=Nextcloud Backup Timer

  [Timer]
  OnCalendar=*-*-* 02:00:00
  Persistent=true

  [Install]
  WantedBy=timers.target
  ```
- Create `deploy/README.md` with deployment instructions.

**Acceptance tests:** Manual — `systemd-analyze verify` on the target Ubuntu server.

**Expected results**
- `README.md` contains Java build and run sections.
- `deploy/` directory contains `.service`, `.timer`, and `README.md`.
- `./gradlew test` still passes with 67 tests green (no regression).

**Demo**
```bash
./gradlew test
# BUILD SUCCESSFUL, 67 tests passed
cat deploy/nextcloud-backup.timer
# Shows OnCalendar=*-*-* 02:00:00
```

---

## Summary Table

| Task | Phase | Class / Artefact | New Tests | Cumulative |
|------|-------|------------------|-----------|------------|
| ✅ 1 | 1 | Gradle build files | 0 | 0 |
| ✅ 2 | 1 | `BackupException` | 2 | 2 |
| ✅ 3 | 1 | `BackupConfig` | 7 | 9 |
| ✅ 4 | 1 | `BackupLogger` + Logback | 3 | 12 |
| ✅ 5 | 1 | `ShellCommand` | 6 | 18 |
| ✅ 6 | 1 | `BackupContext` skeleton | 2 | 20 |
| ✅ 7 | 2 | `DriveService` | 6 | 26 |
| ✅ 8 | 2 | `NextcloudService` | 8 | 34 |
| ✅ 9 | 2 | `NextcloudBackupService` | 5 | 39 |
| ✅ 10 | 2 | `ResticService` | 8 | 47 |
| ✅ 11 | 3 | `NotifierService` | 5 | 52 |
| ✅ 12 | 3 | `BackupContext` (services) | 2 | 54 |
| 13 | 3 | `BackupApplication` orchestration | 3 | 57 |
| 14 | 3 | Integration smoke test | 1 | 58 |
| 15 | 4 | `DatabaseService` | 4 | 62 |
| 16 | 4 | Log formatting parity | 3 | 65 |
| 17 | 4 | `--dry-run` CLI flag | 2 | 67 |
| 18 | 4 | README + systemd units | 0 | 67 |

**Total: 67 automated tests across 18 tasks.**
