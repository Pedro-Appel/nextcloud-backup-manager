package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the Snap-native Nextcloud database export.
 * Maps 1-to-1 with lib/nextcloud_backup.sh.
 */
public class NextcloudBackupService {

    private static final Logger log = BackupLogger.getLogger(NextcloudBackupService.class);

    /** Maximum time allowed for nextcloud.export to complete. */
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(10);

    private final BackupConfig config;
    private final ShellCommand shell;

    /** Path of the most recently created export file; null until exportDb() succeeds. */
    private Path exportPath;

    public NextcloudBackupService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.shell = shell;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates the Nextcloud backup directory if it does not already exist.
     */
    public void init() throws BackupException {
        try {
            Files.createDirectories(config.getNextcloudBackupDir());
            log.debug("Nextcloud backup dir ready: {}", config.getNextcloudBackupDir());
        } catch (IOException e) {
            throw new BackupException(
                    "Cannot create Nextcloud backup dir: " + config.getNextcloudBackupDir(), e);
        }
    }

    /**
     * Runs {@code nextcloud.export -b} and detects the newly created export file
     * by comparing directory contents before and after.
     *
     * @throws BackupException if no new file is detected or the command fails
     */
    public void exportDb() throws BackupException {
        Path backupDir = config.getNextcloudBackupDir();

        log.info("Running export for backup dir: {}", backupDir);
        if (config.isDryRun()) {
            shell.withTimeout(EXPORT_TIMEOUT).run("nextcloud.export", "-b");
            exportPath = backupDir.resolve("dry-run-export");
            log.info("[DRY-RUN] Would create Nextcloud DB export: {}", exportPath);
            return;
        }

        Set<Path> before = listFiles(backupDir);

        log.debug("Found files: {}", before.stream().map(Path::getFileName).limit(5).collect(Collectors.toList()));
        shell.withTimeout(EXPORT_TIMEOUT).run("nextcloud.export", "-b");

        Set<Path> after = listFiles(backupDir);
        log.debug("Found files: {}", after.stream().map(Path::getFileName).limit(5).collect(Collectors.toList()));
        after.removeAll(before);

        if (after.isEmpty()) {
            throw new BackupException(
                    "No new export file detected in " + backupDir + " after nextcloud.export");
        }

        exportPath = after.iterator().next();
        log.info("Nextcloud DB export created: {}", exportPath);
    }

    /**
     * Returns the path of the export file created by the last call to {@link #exportDb()}.
     *
     * @throws IllegalStateException if {@link #exportDb()} has not been called yet
     */
    public Path getExportPath() {
        if (exportPath == null) {
            throw new IllegalStateException(
                    "exportDb() has not been called yet — no export path available");
        }
        return exportPath;
    }

    /**
     * Deletes all regular files inside the Nextcloud backup directory.
     * Called after Restic has snapshotted the exports.
     */
    public void cleanupExports() throws BackupException {
        Path backupDir = config.getNextcloudBackupDir();
        log.info("Cleaning up Nextcloud export files in {}", backupDir);
        try (Stream<Path> files = Files.walk(backupDir)) {
            files.filter(Files::isRegularFile)
                 .forEach(file -> {
                     try {
                         Files.delete(file);
                         log.debug("Deleted export file: {}", file);
                     } catch (IOException e) {
                         log.warn("Could not delete export file {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            throw new BackupException("Failed to walk Nextcloud backup dir: " + backupDir, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Set<Path> listFiles(Path dir) throws BackupException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.collect(Collectors.toSet());
            // return stream.filter(Files::isRegularFile).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new BackupException("Cannot list directory: " + dir, e);
        }
    }
}
