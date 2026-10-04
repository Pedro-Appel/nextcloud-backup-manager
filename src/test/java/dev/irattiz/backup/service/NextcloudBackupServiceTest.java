package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.util.ShellCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NextcloudBackupServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    BackupConfig config;

    @Mock
    ShellCommand shell;

    Path backupDir;
    NextcloudBackupService service;

    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.resolve("nextcloud-backups");
        Files.createDirectories(backupDir);
        when(config.getNextcloudBackupDir()).thenReturn(backupDir);
        // withTimeout must return a shell mock so exportDb() can call run() on it
        when(shell.withTimeout(any(Duration.class))).thenReturn(shell);
        service = new NextcloudBackupService(config, shell);
    }

    // ------------------------------------------------------------------
    // Test 1: initCreatesBackupDirectory
    // ------------------------------------------------------------------

    @Test
    void initCreatesBackupDirectory() throws BackupException {
        Path newDir = tempDir.resolve("new-backup-dir");
        when(config.getNextcloudBackupDir()).thenReturn(newDir);
        NextcloudBackupService svc = new NextcloudBackupService(config, shell);

        svc.init();

        assertTrue(Files.isDirectory(newDir), "init() should create the backup directory");
    }

    // ------------------------------------------------------------------
    // Test 2: exportDbDetectsNewFile
    // ------------------------------------------------------------------

    @Test
    void exportDbDetectsNewFile() throws Exception {
        // Simulate nextcloud.export creating a new file during the shell call
        doAnswer(invocation -> {
            Files.createFile(backupDir.resolve("nextcloud-export-2026-01-01.tar.gz"));
            return null;
        }).when(shell).run("nextcloud.export", "-b");

        service.exportDb();

        assertNotNull(service.getExportPath());
        assertEquals(backupDir, service.getExportPath().getParent());
    }

    // ------------------------------------------------------------------
    // Test 3: exportDbThrowsWhenNoNewFileDetected
    // ------------------------------------------------------------------

    @Test
    void exportDbThrowsWhenNoNewFileDetected() throws BackupException {
        // Shell does nothing — no new file appears
        doNothing().when(shell).run("nextcloud.export", "-b");

        BackupException ex = assertThrows(BackupException.class, () -> service.exportDb());
        assertTrue(ex.getMessage().contains("No new export"),
                "Exception should mention 'No new export', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 4: getExportPathThrowsBeforeExport
    // ------------------------------------------------------------------

    @Test
    void getExportPathThrowsBeforeExport() {
        assertThrows(IllegalStateException.class, () -> service.getExportPath());
    }

    // ------------------------------------------------------------------
    // Test 5: cleanupExportsDeletesAllFiles
    // ------------------------------------------------------------------

    @Test
    void cleanupExportsDeletesAllFiles() throws Exception {
        // Create three files in the backup dir
        Files.createFile(backupDir.resolve("export-1.tar.gz"));
        Files.createFile(backupDir.resolve("export-2.tar.gz"));
        Files.createFile(backupDir.resolve("export-3.tar.gz"));

        service.cleanupExports();

        // All files deleted, but directory itself still exists
        assertTrue(Files.isDirectory(backupDir), "Directory should still exist after cleanup");
        assertEquals(0, Files.list(backupDir).count(), "All files should have been deleted");
    }
}
