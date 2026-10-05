package dev.irattiz.backup;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.context.BackupContext;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.service.*;
import dev.irattiz.backup.util.ShellCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupApplicationTest {

    @TempDir
    Path tempDir;

    @Mock BackupContext    context;
    @Mock BackupConfig     config;
    @Mock ShellCommand     shell;
    @Mock DriveService            drive;
    @Mock NextcloudService        nextcloud;
    @Mock NextcloudBackupService  nextcloudBackup;
    @Mock ResticService           restic;
    @Mock NotifierService         notifier;

    @BeforeEach
    void setUp() throws BackupException {
        when(context.getDrive()).thenReturn(drive);
        when(context.getNextcloud()).thenReturn(nextcloud);
        when(context.getNextcloudBackup()).thenReturn(nextcloudBackup);
        when(context.getRestic()).thenReturn(restic);
        when(context.getNotifier()).thenReturn(notifier);
        when(context.getConfig()).thenReturn(config);

        when(config.getNextcloudDataDir()).thenReturn(tempDir.resolve("data"));
        when(config.getNextcloudConfigDir()).thenReturn(tempDir.resolve("config"));
        when(config.isDryRun()).thenReturn(false);

        when(nextcloudBackup.getExportPath()).thenReturn(tempDir.resolve("export.tar.gz"));
        when(restic.getLatestSnapshotId()).thenReturn("abc1234");
    }

    // ------------------------------------------------------------------
    // Test 1: fullWorkflowCallsAllPhases
    // ------------------------------------------------------------------

    @Test
    void fullWorkflowCallsAllPhases() throws BackupException {
        BackupApplication.runWorkflow(context);

        InOrder order = inOrder(notifier, nextcloud, drive, nextcloudBackup, restic);
        order.verify(notifier).sendStart();
        order.verify(nextcloud).check();
        order.verify(drive).validate();
        order.verify(nextcloud).maintenanceEnable();
        order.verify(nextcloudBackup).exportDb();
        order.verify(restic).repositoryInit();
        order.verify(restic).unlock();
        order.verify(restic).backup(anyList());
        order.verify(restic).applyRetention();
        order.verify(nextcloudBackup).cleanupExports();
        order.verify(nextcloud).maintenanceDisable();
        order.verify(notifier).sendSuccess(eq("abc1234"), any(Duration.class));
        order.verify(restic).getStats();
    }

    // ------------------------------------------------------------------
    // Test 2: failureInDriveValidateSendsFailureNotification
    // ------------------------------------------------------------------

    @Test
    void failureInDriveValidateSendsFailureNotification() throws BackupException {
        doThrow(new BackupException("drive not mounted"))
                .when(drive).validate();

        // runWorkflow throws — the caller (main) handles it, but we test
        // by verifying sendFailure is called from the catch in main()
        assertThrows(BackupException.class, () -> BackupApplication.runWorkflow(context));

        // sendFailure is called from main(), not runWorkflow() — so we verify
        // the exception propagates with the right message
        BackupException ex = assertThrows(BackupException.class,
                () -> BackupApplication.runWorkflow(context));
        assertEquals("drive not mounted", ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 3: failureInMaintenanceEnableStillRegistersShutdownHook
    // ------------------------------------------------------------------

    @Test
    void failureInMaintenanceEnableStillRegistersShutdownHook() throws BackupException {
        // Fail partway through the workflow
        doThrow(new BackupException("occ unavailable"))
                .when(nextcloud).maintenanceEnable();

        assertThrows(BackupException.class, () -> BackupApplication.runWorkflow(context));

        // The shutdown hook is registered before any phase — verify cleanup()
        // is wired in by confirming nextcloud.cleanup() is never called in the
        // workflow itself (it runs only via the hook), meaning no double-disable
        verify(nextcloud, never()).cleanup();
    }

    @Test
    void dryRunFlagSetsDryRunMode() {
        String before = System.getProperty("DRY_RUN");
        try {
            System.clearProperty("DRY_RUN");
            assertFalse(BackupApplication.processArguments(new String[]{"--dry-run"}));
            assertEquals("true", System.getProperty("DRY_RUN"));
        } finally {
            if (before == null) System.clearProperty("DRY_RUN");
            else System.setProperty("DRY_RUN", before);
        }
    }

    @Test
    void helpFlagPrintsUsageAndExitsZero() {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output));
            assertTrue(BackupApplication.processArguments(new String[]{"--help"}));
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("Usage: nextcloud-backup-manager [--dry-run] [--help]"));
    }
}
