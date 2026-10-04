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

    /** Creates a minimal valid config/backup.conf under the given root. */
    private void writeValidConfig(Path root) throws IOException {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Properties p = new Properties();
        p.setProperty("BACKUP_MOUNT", "/mnt/backup");
        p.setProperty("BACKUP_DEVICE_UUID", "test-uuid");
        p.setProperty("RESTIC_REPOSITORY", "/mnt/backup/restic");
        p.setProperty("RESTIC_PASSWORD_FILE", "/etc/restic.pass");
        p.setProperty("RESTIC_RETENTION_DAILY", "7");
        p.setProperty("RESTIC_RETENTION_WEEKLY", "4");
        p.setProperty("RESTIC_RETENTION_MONTHLY", "12");
        p.setProperty("NEXTCLOUD_OCC", "/snap/bin/nextcloud.occ");
        p.setProperty("NOTIFIER_DIR", "/opt/notifier");
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

        assertNotNull(context.getConfig(), "getConfig() must not return null");
        assertNotNull(context.getShell(),  "getShell() must not return null");
        assertEquals(tempDir.toAbsolutePath().normalize(), context.projectRoot());
    }

    // ------------------------------------------------------------------
    // Test 2: throwsWhenConfigMissing
    // ------------------------------------------------------------------

    @Test
    void throwsWhenConfigMissing() {
        // tempDir exists but has no config/backup.conf inside it
        assertThrows(BackupException.class, () -> new BackupContext(tempDir));
    }
}
