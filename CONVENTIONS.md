# Conventions

These conventions describe the current Java implementation. The Bash migration has been completed; new application work belongs in `src/main/java` and its tests in `src/test/java`.

## Java structure and naming

- Keep production code in the `dev.irattiz.backup` package and group classes by responsibility (`config`, `context`, `exception`, `logging`, `service`, `util`).
- Use descriptive camelCase method and variable names, and `UPPER_SNAKE_CASE` for constants.
- Keep `BackupApplication` focused on CLI handling and workflow order. Put external-system behavior in the relevant service and wire dependencies in `BackupContext`.
- Use `BackupException` for expected operational failures that should reach the application boundary with context.
- Use constructor injection for service dependencies. The project has no dependency injection framework.

## Configuration

- Runtime settings belong in `config/backup.conf`, loaded as Java properties by `BackupConfig`.
- Add typed getters and validation in `BackupConfig` when introducing settings.
- Path keys ending in `_DIR`, `_FILE`, `_REPOSITORY`, or `_MOUNT` must be absolute. Required values must be non-blank, and retention counts must be positive integers.
- Keep Snap defaults in `BackupConfig`; defaults currently cover `NEXTCLOUD_DATA_DIR`, `NEXTCLOUD_CONFIG_DIR`, and `NEXTCLOUD_BACKUP_DIR`.
- Document precedence when environment variables or command-line flags override a file setting. Current dry-run precedence is environment, `--dry-run`, config, then `false`; `RESTIC_PROGRESS_FPS` from the environment overrides the config value.

## Logging

- Use SLF4J through `BackupLogger.getLogger(Class<?>)` in application classes.
- Keep Logback-specific setup inside `BackupLogger` and `src/main/resources/logback.xml`.
- Use INFO for operational phases and summaries, WARN for recoverable issues, ERROR for failed workflows, and DEBUG for subprocess detail.
- Use `BackupLogger.logSection` and `BackupLogger.logPhaseEnd` around major workflow phases.

## External commands and dry-run

- Run subprocesses through `ShellCommand`; services should not create their own `ProcessBuilder` instances.
- Use the timeout wrapper for commands that may hang. OCC uses the configured timeout; the Snap export uses a fixed ten-minute timeout.
- Redact secrets before logging commands. `ShellCommand.runTo` masks mysqldump password arguments.
- Make dry-run behavior explicit for filesystem writes and external integrations. The shared command runner skips subprocess execution in dry-run mode; Restic backup also supports the native `--dry-run` flag when used directly.

## Tests and build

- Use JUnit 5 and Mockito for automated tests, following the existing package layout under `src/test/java`.
- Keep ordinary unit tests in the default test suite. Tag end-to-end tests with `integration`; `./gradlew test` excludes that tag and `./gradlew integrationTest` runs it.
- Build the production artifact with `./gradlew shadowJar`. The artifact uses the `all` classifier unless the deployment pipeline renames it.

## Deployment

- The systemd service and timer are maintained in `deploy/` and installed under `/etc/systemd/system/`.
- The service expects the stable JAR at `/opt/backup/nextcloud-backup-manager.jar` and config at `/opt/backup/config/backup.conf`.
- Keep the deployment procedure in [`deploy/README.md`](deploy/README.md) aligned with `Jenkinsfile`, `deploy/deploy-nextcloud-backup.sh`, and the checked-in unit files.
