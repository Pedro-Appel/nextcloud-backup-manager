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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NextcloudServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    BackupConfig config;

    @Mock
    ShellCommand shell;

    NextcloudService service;

    @BeforeEach
    void setUp() {
        // withTimeout returns the same mock shell for simplicity
        when(shell.withTimeout(any(Duration.class))).thenReturn(shell);
        when(config.getOccTimeout()).thenReturn(Duration.ofSeconds(30));
        when(config.getNextcloudOcc()).thenReturn(tempDir.resolve("nextcloud.occ"));
        service = new NextcloudService(config, shell);
    }

    // ------------------------------------------------------------------
    // Test 1: checkThrowsWhenOccNotExecutable
    // ------------------------------------------------------------------

    @Test
    void checkThrowsWhenOccNotExecutable() {
        // Path points to a non-existent file → Files.isExecutable returns false
        BackupException ex = assertThrows(BackupException.class, () -> service.check());
        assertTrue(ex.getMessage().contains("executable"),
                "Message should mention 'executable', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 2: checkPassesWhenOccExists
    // ------------------------------------------------------------------

    @Test
    void checkPassesWhenOccExists() throws IOException, BackupException {
        Path occ = tempDir.resolve("nextcloud.occ");
        Files.createFile(occ);
        occ.toFile().setExecutable(true);
        when(config.getNextcloudOcc()).thenReturn(occ);

        assertDoesNotThrow(() -> service.check());
    }

    // ------------------------------------------------------------------
    // Test 3: maintenanceEnableRunsCorrectCommand
    // ------------------------------------------------------------------

    @Test
    void maintenanceEnableRunsCorrectCommand() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        service.maintenanceEnable();
        verify(shell).run(occPath, "maintenance:mode", "--on");
    }

    // ------------------------------------------------------------------
    // Test 4: maintenanceDisableRunsCorrectCommand
    // ------------------------------------------------------------------

    @Test
    void maintenanceDisableRunsCorrectCommand() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        service.maintenanceDisable();
        verify(shell).run(occPath, "maintenance:mode", "--off");
    }

    // ------------------------------------------------------------------
    // Test 5: isMaintenanceReturnsTrueWhenEnabled
    // ------------------------------------------------------------------

    @Test
    void isMaintenanceReturnsTrueWhenEnabled() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        when(shell.runCapture(occPath, "maintenance:mode"))
                .thenReturn("Maintenance mode: enabled");

        assertTrue(service.isMaintenance());
    }

    // ------------------------------------------------------------------
    // Test 6: isMaintenanceReturnsFalseWhenDisabled
    // ------------------------------------------------------------------

    @Test
    void isMaintenanceReturnsFalseWhenDisabled() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        when(shell.runCapture(occPath, "maintenance:mode"))
                .thenReturn("Maintenance mode: disabled");

        assertFalse(service.isMaintenance());
    }

    // ------------------------------------------------------------------
    // Test 7: cleanupDisablesMaintenanceWhenActive
    // ------------------------------------------------------------------

    @Test
    void cleanupDisablesMaintenanceWhenActive() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        when(shell.runCapture(occPath, "maintenance:mode"))
                .thenReturn("Maintenance mode: enabled");

        service.cleanup();

        verify(shell).run(occPath, "maintenance:mode", "--off");
    }

    // ------------------------------------------------------------------
    // Test 8: cleanupDoesNotThrowOnError
    // ------------------------------------------------------------------

    @Test
    void cleanupDoesNotThrowOnError() throws BackupException {
        String occPath = config.getNextcloudOcc().toString();
        when(shell.runCapture(occPath, "maintenance:mode"))
                .thenThrow(new BackupException("occ unreachable"));

        // Must complete without throwing regardless of the error
        assertDoesNotThrow(() -> service.cleanup());
    }
}
