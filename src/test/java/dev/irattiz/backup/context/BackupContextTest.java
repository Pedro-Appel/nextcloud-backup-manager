package dev.irattiz.backup.context;

import dev.irattiz.backup.exception.BackupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BackupContextTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Writes a minimal valid config/backup.conf under root.
     * Paths are set to non-existent but absolute paths so BackupConfig validates OK.
     * restic.init() is skipped in tests by pointing the password file to a real temp file
     * and mocking "which restic" via dry-run (shell never executes in dry-run mode).
     */
    private void writeValidConfig(Path root) throws IOException {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);

        // Create a real password file so ResticService.init() passes the Files.exists check
        Path passFile = root.resolve("restic.pass");
        Files.createFile(passFile);

        Properties p = new Properties();
        p.setProperty("BACKUP_MOUNT",           "/mnt/backup");
        p.setProperty("BACKUP_DEVICE_UUID",     "test-uuid");
        p.setProperty("RESTIC_REPOSITORY",      "/mnt/backup/restic");
        p.setProperty("RESTIC_PASSWORD_FILE",   passFile.toAbsolutePath().toString());
        p.setProperty("RESTIC_RETENTION_DAILY",   "7");
        p.setProperty("RESTIC_RETENTION_WEEKLY",  "4");
        p.setProperty("RESTIC_RETENTION_MONTHLY", "12");
        p.setProperty("NEXTCLOUD_OCC",          "/snap/bin/nextcloud.occ");
        p.setProperty("NOTIFIER_DIR",           "/opt/notifier");
        // Point Nextcloud dirs into tempDir so NextcloudBackupService.init() can create them
        p.setProperty("NEXTCLOUD_DATA_DIR",     root.resolve("nc-data").toAbsolutePath().toString());
        p.setProperty("NEXTCLOUD_CONFIG_DIR",   root.resolve("nc-config").toAbsolutePath().toString());
        p.setProperty("NEXTCLOUD_BACKUP_DIR",   root.resolve("nc-backups").toAbsolutePath().toString());
        // DRY_RUN=true keeps ShellCommand from actually running "which restic"
        p.setProperty("DRY_RUN",                "true");
        try (var out = Files.newOutputStream(configDir.resolve("backup.conf"))) {
            p.store(out, null);
        }
    }

    // ------------------------------------------------------------------
    // Test 1: initialisesWithValidConfig
    // ------------------------------------------------------------------

    @Test
    void initialisesWithValidConfig() throws Exception {
        writeValidConfig(tempDir);
        BackupContext context = new BackupContext(tempDir);

        assertNotNull(context.getConfig());
        assertNotNull(context.getShell());
        assertEquals(tempDir.toAbsolutePath().normalize(), context.projectRoot());
    }

    // ------------------------------------------------------------------
    // Test 2: throwsWhenConfigMissing
    // ------------------------------------------------------------------

    @Test
    void throwsWhenConfigMissing() {
        assertThrows(BackupException.class, () -> new BackupContext(tempDir));
    }

    // ------------------------------------------------------------------
    // Test 3: exposesAllServices
    // ------------------------------------------------------------------

    @Test
    void exposesAllServices() throws Exception {
        writeValidConfig(tempDir);
        BackupContext context = new BackupContext(tempDir);

        assertNotNull(context.getDrive(),           "getDrive() must not be null");
        assertNotNull(context.getNextcloud(),       "getNextcloud() must not be null");
        assertNotNull(context.getNextcloudBackup(), "getNextcloudBackup() must not be null");
        assertNotNull(context.getRestic(),          "getRestic() must not be null");
        assertNotNull(context.getNotifier(),        "getNotifier() must not be null");
    }

    // ------------------------------------------------------------------
    // Test 4: throwsWhenResticInitFails
    // ------------------------------------------------------------------

    @Test
    void throwsWhenResticInitFails() throws IOException {
        writeValidConfig(tempDir);

        // Override RESTIC_PASSWORD_FILE to a path that does not exist so
        // ResticService.init() throws. We do this by removing the pass file after
        // writing the config — the file path in config still points to it.
        Path passFile = tempDir.resolve("restic.pass");
        Files.deleteIfExists(passFile);

        // DRY_RUN=true means "which restic" won't be called, but the password
        // file check uses Files.exists() directly — so it will still fail.
        BackupException ex = assertThrows(BackupException.class,
                () -> new BackupContext(tempDir));
        assertTrue(ex.getMessage().toLowerCase().contains("password"),
                "Exception should mention password file, was: " + ex.getMessage());
    }
}
