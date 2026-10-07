# Java migration plan (completed)

The Bash-to-Java migration is complete. This file records the delivered scope so it is not mistaken for a list of outstanding work. Current behavior and operations are documented in [`SPEC.md`](SPEC.md), [`ARCHITECTURE.md`](ARCHITECTURE.md), and [`README.md`](README.md).

## Delivered

- Java 21 CLI application built with Gradle Kotlin DSL and packaged as a self-contained Shadow JAR.
- Typed configuration loading, validation, Snap defaults, environment overrides, and dry-run support.
- SLF4J/Logback console and rolling file logging.
- Shared subprocess execution with capture, streaming, timeouts, and error reporting.
- Services for backup drive validation, Nextcloud maintenance mode and Snap export, Restic repository/backup/retention operations, and lifecycle notifications.
- Supplementary direct Snap `mysqldump` support in `DatabaseService`; this is not part of the default backup workflow.
- Unit tests and tagged dry-run integration tests, with separate Gradle tasks.
- systemd service and timer definitions plus a Jenkins deployment pipeline and deployment helper.

## Current implementation references

- Workflow and CLI: `src/main/java/dev/irattiz/backup/BackupApplication.java`
- Configuration and defaults: `src/main/java/dev/irattiz/backup/config/BackupConfig.java`
- Service wiring: `src/main/java/dev/irattiz/backup/context/BackupContext.java`
- Build and test tasks: `build.gradle.kts`
- Deployment: `deploy/nextcloud-backup.service`, `deploy/nextcloud-backup.timer`, `deploy/deploy-nextcloud-backup.sh`, and `Jenkinsfile`

## Verification

Run these commands when making changes to the application:

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar
```
