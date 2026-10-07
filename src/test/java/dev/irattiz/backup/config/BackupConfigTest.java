package dev.irattiz.backup.config;

import ch.qos.logback.classic.Level;
import dev.irattiz.backup.exception.BackupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BackupConfigTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Writes a Properties object to a temp file and returns its path. */
    private Path writeConfig(Properties props) throws IOException {
        Path file = tempDir.resolve("backup.conf");
        try (var out = Files.newOutputStream(file)) {
            props.store(out, null);
        }
        return file;
    }

    /** Returns a fully valid set of config properties. */
    private Properties validProps() {
        Properties p = new Properties();
        p.setProperty("BACKUP_MOUNT", "/mnt/backup");
        p.setProperty("BACKUP_DEVICE_UUID", "c2117669-a245-4682-805f-f94f81e27ee1");
        p.setProperty("RESTIC_REPOSITORY", "/mnt/backup/restic");
        p.setProperty("RESTIC_PASSWORD_FILE", "/etc/backup-service/restic.pass");
        p.setProperty("RESTIC_TAG", "nextcloud");
        p.setProperty("RESTIC_PROGRESS_FPS", "0.033333");
        p.setProperty("RESTIC_RETENTION_DAILY", "7");
        p.setProperty("RESTIC_RETENTION_WEEKLY", "4");
        p.setProperty("RESTIC_RETENTION_MONTHLY", "12");
        p.setProperty("NEXTCLOUD_OCC", "/snap/bin/nextcloud.occ");
        p.setProperty("NOTIFIER_DIR", "/opt/home-lab/notifier");
        p.setProperty("LOG_LEVEL", "INFO");
        p.setProperty("NEXTCLOUD_OCC_TIMEOUT", "30");
        return p;
    }

    // ------------------------------------------------------------------
    // Test 1: loadsValidConfig
    // ------------------------------------------------------------------

    @Test
    void loadsValidConfig() throws Exception {
        Path file = writeConfig(validProps());
        BackupConfig config = new BackupConfig(file);

        assertEquals(Path.of("/mnt/backup"), config.getBackupMount());
        assertEquals("c2117669-a245-4682-805f-f94f81e27ee1", config.getBackupDeviceUuid());
        assertEquals(Path.of("/mnt/backup/restic"), config.getResticRepository());
        assertEquals(Path.of("/etc/backup-service/restic.pass"), config.getResticPasswordFile());
        assertEquals("nextcloud", config.getResticTag());
        assertEquals(0.033333, config.getResticProgressFps());
        assertEquals(7, config.getResticRetentionDaily());
        assertEquals(4, config.getResticRetentionWeekly());
        assertEquals(12, config.getResticRetentionMonthly());
        assertEquals(Path.of("/snap/bin/nextcloud.occ"), config.getNextcloudOcc());
        assertEquals(Path.of("/opt/home-lab/notifier"), config.getNotifierDir());
        assertEquals(Level.INFO, config.getLogLevel());
        assertEquals(Duration.ofSeconds(30), config.getOccTimeout());
    }

    // ------------------------------------------------------------------
    // Test 2: throwsOnMissingRequiredKey
    // ------------------------------------------------------------------

    @Test
    void throwsOnMissingRequiredKey() throws Exception {
        Properties p = validProps();
        p.remove("RESTIC_REPOSITORY");
        Path file = writeConfig(p);

        BackupException ex = assertThrows(BackupException.class, () -> new BackupConfig(file));
        assertTrue(ex.getMessage().contains("RESTIC_REPOSITORY"),
                "Exception message should name the missing key, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 3: throwsOnRelativePath
    // ------------------------------------------------------------------

    @Test
    void throwsOnRelativePath() throws Exception {
        Properties p = validProps();
        p.setProperty("BACKUP_MOUNT", "relative/path");
        Path file = writeConfig(p);

        BackupException ex = assertThrows(BackupException.class, () -> new BackupConfig(file));
        assertTrue(ex.getMessage().toLowerCase().contains("absolute"),
                "Exception message should mention 'absolute', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 4: throwsOnNonIntegerRetention
    // ------------------------------------------------------------------

    @Test
    void throwsOnNonIntegerRetention() throws Exception {
        Properties p = validProps();
        p.setProperty("RESTIC_RETENTION_DAILY", "abc");
        Path file = writeConfig(p);

        assertThrows(BackupException.class, () -> new BackupConfig(file));
    }

    @Test
    void throwsOnBlankResticTag() throws Exception {
        Properties p = validProps();
        p.setProperty("RESTIC_TAG", "  ");
        Path file = writeConfig(p);

        BackupException ex = assertThrows(BackupException.class, () -> new BackupConfig(file));
        assertTrue(ex.getMessage().contains("RESTIC_TAG"));
    }

    @Test
    void environmentOverridesResticProgressFps() throws Exception {
        Path file = writeConfig(validProps());
        BackupConfig config = new BackupConfig(
                file,
                name -> "RESTIC_PROGRESS_FPS".equals(name) ? "0.1" : null);

        assertEquals(0.1, config.getResticProgressFps());
    }

    @Test
    void throwsOnInvalidResticProgressFps() throws Exception {
        Properties p = validProps();
        p.setProperty("RESTIC_PROGRESS_FPS", "0");
        Path file = writeConfig(p);

        BackupException ex = assertThrows(BackupException.class, () -> new BackupConfig(file));
        assertTrue(ex.getMessage().contains("RESTIC_PROGRESS_FPS"));
    }

    // ------------------------------------------------------------------
    // Test 5: envVarOverridesDryRun
    // ------------------------------------------------------------------

    @Test
    void envVarOverridesDryRun() throws Exception {
        Properties p = validProps();
        p.setProperty("DRY_RUN", "false");   // config says false
        Path file = writeConfig(p);

        // Inject env supplier that returns "true" — must win over config
        BackupConfig config = new BackupConfig(file, name -> "DRY_RUN".equals(name) ? "true" : null);

        assertTrue(config.isDryRun(), "Env var DRY_RUN=true should override config value false");
    }

    // ------------------------------------------------------------------
    // Test 6: autoDetectsSnapDefaults
    // ------------------------------------------------------------------

    @Test
    void autoDetectsSnapDefaults() throws Exception {
        Properties p = validProps();
        // Do NOT set NEXTCLOUD_DATA_DIR, NEXTCLOUD_CONFIG_DIR, NEXTCLOUD_BACKUP_DIR
        Path file = writeConfig(p);

        BackupConfig config = new BackupConfig(file);

        assertEquals(
                Path.of("/var/snap/nextcloud/current/nextcloud/data"),
                config.getNextcloudDataDir(),
                "Should auto-detect Snap default for NEXTCLOUD_DATA_DIR"
        );
        assertEquals(
                Path.of("/var/snap/nextcloud/current/nextcloud/config"),
                config.getNextcloudConfigDir()
        );
        assertEquals(
                Path.of("/var/snap/nextcloud/common/backups"),
                config.getNextcloudBackupDir()
        );
    }

    // ------------------------------------------------------------------
    // Test 7: occTimeoutDefaultsTo30s
    // ------------------------------------------------------------------

    @Test
    void occTimeoutDefaultsTo30s() throws Exception {
        Properties p = validProps();
        p.remove("NEXTCLOUD_OCC_TIMEOUT");
        Path file = writeConfig(p);

        BackupConfig config = new BackupConfig(file);

        assertEquals(Duration.ofSeconds(30), config.getOccTimeout());
    }
}
