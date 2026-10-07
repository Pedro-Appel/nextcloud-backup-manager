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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DriveServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    BackupConfig config;

    @Mock
    ShellCommand shell;

    DriveService service;

    @BeforeEach
    void setUp() {
        when(config.getBackupMount()).thenReturn(tempDir);
        when(config.getBackupDeviceUuid()).thenReturn("test-uuid");
        service = new DriveService(config, shell);
    }

    // ------------------------------------------------------------------
    // Test 1: validateMountsDriveWhenNotMounted
    // ------------------------------------------------------------------

    @Test
    void validateMountsDriveWhenNotMounted() throws BackupException {
        // isMounted() calls shell.run("mountpoint","-q",...) — make it throw → returns false
        doThrow(new BackupException("not mounted"))
                .when(shell).run("mountpoint", "-q", tempDir.toString());

        // mount() calls runCapture for blkid then run for mount
        when(shell.runCapture("blkid", "-U", "test-uuid")).thenReturn("/dev/sdb1");

        // checkSpace() — return a healthy df output
        when(shell.runCapture("df", "-k", tempDir.toString()))
                .thenReturn("Filesystem 1K-blocks Used Available Use% Mounted\n" +
                             "/dev/sdb1  20971520  1000 10000000   5% " + tempDir);

        service.validate();

        // Verify mount was called with the device from blkid
        verify(shell).run("mount", "/dev/sdb1", tempDir.toString());
    }

    // ------------------------------------------------------------------
    // Test 2: validateSkipsMountWhenAlreadyMounted
    // ------------------------------------------------------------------

    @Test
    void validateSkipsMountWhenAlreadyMounted() throws BackupException {
        // isMounted() — run succeeds → returns true
        doNothing().when(shell).run("mountpoint", "-q", tempDir.toString());

        when(shell.runCapture("df", "-k", tempDir.toString()))
                .thenReturn("Filesystem 1K-blocks Used Available Use% Mounted\n" +
                             "/dev/sdb1  20971520  1000 10000000   5% " + tempDir);

        service.validate();

        // mount() must NOT have been called
        verify(shell, never()).run(eq("mount"), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Test 3: checkSpaceThrowsWhenBelowMinimum
    // ------------------------------------------------------------------

    @Test
    void checkSpaceThrowsWhenBelowMinimum() throws BackupException {
        // ~1 GiB available (1_000_000 KB)
        when(shell.runCapture("df", "-k", tempDir.toString()))
                .thenReturn("Filesystem 1K-blocks Used Available Use% Mounted\n" +
                             "/dev/sdb1  20971520  1000  1000000   5% " + tempDir);

        BackupException ex = assertThrows(BackupException.class, () -> service.checkSpace());
        assertTrue(ex.getMessage().toLowerCase().contains("space"),
                "Exception should mention space, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 4: checkSpacePassesWhenSufficient
    // ------------------------------------------------------------------

    @Test
    void checkSpacePassesWhenSufficient() throws BackupException {
        // 10_000_000 KB ≈ 9.5 GiB — well above the 5 GiB minimum
        when(shell.runCapture("df", "-k", tempDir.toString()))
                .thenReturn("Filesystem 1K-blocks Used Available Use% Mounted\n" +
                             "/dev/sdb1  20971520  1000 10000000   5% " + tempDir);

        assertDoesNotThrow(() -> service.checkSpace());
    }

    // ------------------------------------------------------------------
    // Test 5: mountUsesBlkidOutput
    // ------------------------------------------------------------------

    @Test
    void mountUsesBlkidOutput() throws BackupException {
        when(shell.runCapture("blkid", "-U", "test-uuid")).thenReturn("/dev/sdb1");

        service.mount();

        verify(shell).run("mount", "/dev/sdb1", tempDir.toString());
    }

    // ------------------------------------------------------------------
    // Test 6: checkWritableThrowsOnReadOnlyFs
    // ------------------------------------------------------------------

    @Test
    void checkWritableThrowsOnReadOnlyFs() throws IOException {
        // Make a subdirectory read-only so Files.createFile throws IOException
        Path readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectories(readOnlyDir);
        readOnlyDir.toFile().setWritable(false);

        when(config.getBackupMount()).thenReturn(readOnlyDir);
        DriveService roService = new DriveService(config, shell);

        assertThrows(BackupException.class, roService::checkWritable);

        // Restore permissions so @TempDir cleanup works
        readOnlyDir.toFile().setWritable(true);
    }
}
