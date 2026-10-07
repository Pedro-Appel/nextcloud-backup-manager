package dev.irattiz.backup;

import dev.irattiz.backup.context.BackupContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class BackupApplicationIT {
    @TempDir Path projectRoot;

    @Test
    void dryRunWorkflowLogsEveryPhaseWithoutExternalSystems() throws Exception {
        Path configDir = Files.createDirectories(projectRoot.resolve("config"));
        Path password = Files.createFile(projectRoot.resolve("restic.pass"));
        Properties properties = new Properties();
        properties.setProperty("BACKUP_MOUNT", "/nonexistent/backup");
        properties.setProperty("BACKUP_DEVICE_UUID", "dry-run-device");
        properties.setProperty("RESTIC_REPOSITORY", "/nonexistent/backup/restic");
        properties.setProperty("RESTIC_PASSWORD_FILE", password.toString());
        properties.setProperty("RESTIC_TAG", "nextcloud");
        properties.setProperty("RESTIC_PROGRESS_FPS", "0.033333");
        properties.setProperty("RESTIC_RETENTION_DAILY", "7");
        properties.setProperty("RESTIC_RETENTION_WEEKLY", "4");
        properties.setProperty("RESTIC_RETENTION_MONTHLY", "12");
        properties.setProperty("NEXTCLOUD_OCC", "/nonexistent/nextcloud.occ");
        properties.setProperty("NOTIFIER_DIR", "/nonexistent/notifier");
        properties.setProperty("NEXTCLOUD_DATA_DIR", "/nonexistent/nextcloud/data");
        properties.setProperty("NEXTCLOUD_CONFIG_DIR", "/nonexistent/nextcloud/config");
        properties.setProperty("NEXTCLOUD_BACKUP_DIR", projectRoot.resolve("snap-backups").toString());
        properties.setProperty("DRY_RUN", "true");
        try (var output = Files.newOutputStream(configDir.resolve("backup.conf"))) {
            properties.store(output, "integration dry-run config");
        }

        BackupContext context = new BackupContext(projectRoot);
        assertDoesNotThrow(() -> BackupApplication.runWorkflow(context));

        Path logFile = projectRoot.resolve("log/backup-" + LocalDate.now() + ".log");
        assertTrue(Files.isRegularFile(logFile), "Expected daily log at " + logFile);
        String log = Files.readString(logFile);
        assertTrue(log.contains("[DRY-RUN]"));
        assertTrue(log.contains("=== Restic backup ==="));
        assertTrue(log.contains("=== End consistency boundary ==="));

        Path jar = Path.of(System.getProperty("applicationJar"));
        Path isolatedJar = Files.createDirectories(projectRoot.resolve("build/libs"))
                .resolve("nextcloud-backup-manager-all.jar");
        Files.copy(jar, isolatedJar);
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", isolatedJar.toString(), "--dry-run")
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "fat JAR dry-run did not finish");
        assertEquals(0, process.exitValue(), output.toString());
        log = Files.readString(logFile);
        assertTrue(log.contains("[DRY-RUN]"));
        assertTrue(log.contains("=== Restic backup ==="));
    }
}
