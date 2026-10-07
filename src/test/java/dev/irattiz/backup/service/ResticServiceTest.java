package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.util.ShellCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResticServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    BackupConfig config;

    @Mock
    ShellCommand shell;

    ResticService service;

    @BeforeEach
    void setUp() throws IOException, BackupException {
        Path repo = tempDir.resolve("restic-repo");
        Path passFile = tempDir.resolve("restic.pass");
        Files.createFile(passFile);

        when(config.getResticRepository()).thenReturn(repo);
        when(config.getResticPasswordFile()).thenReturn(passFile);
        when(config.getResticTag()).thenReturn("nextcloud");
        when(config.getResticRetentionDaily()).thenReturn(7);
        when(config.getResticRetentionWeekly()).thenReturn(4);
        when(config.getResticRetentionMonthly()).thenReturn(12);
        when(config.getResticProgressFps()).thenReturn(0.033333);
        when(config.isDryRun()).thenReturn(false);

        service = new ResticService(config, shell);
    }

    // ------------------------------------------------------------------
    // Test 1: initThrowsWhenResticMissing
    // ------------------------------------------------------------------

    @Test
    void initThrowsWhenResticMissing() throws BackupException {
        when(shell.runCapture("which", "restic"))
                .thenThrow(new BackupException("which: restic not found"));

        BackupException ex = assertThrows(BackupException.class, () -> service.init());
        assertTrue(ex.getMessage().toLowerCase().contains("restic"),
                "Exception should mention 'restic', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 2: initThrowsWhenPasswordFileMissing
    // ------------------------------------------------------------------

    @Test
    void initThrowsWhenPasswordFileMissing() throws BackupException, IOException {
        when(shell.runCapture("which", "restic")).thenReturn("/usr/bin/restic");

        // Point to a non-existent password file
        Path missing = tempDir.resolve("missing.pass");
        when(config.getResticPasswordFile()).thenReturn(missing);

        BackupException ex = assertThrows(BackupException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("password"),
                "Exception should mention password file, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 3: repositoryInitSkipsWhenExists
    // ------------------------------------------------------------------

    @Test
    void repositoryInitSkipsWhenExists() throws BackupException {
        // repositoryExists() probes with restic snapshots — make it succeed
        doNothing().when(shell).run(eq("restic"), eq("snapshots"), any(), any(), any(), any());

        service.repositoryInit();

        // restic init must NOT be called
        verify(shell, never()).run(eq("restic"), eq("init"), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Test 4: repositoryInitRunsWhenMissing
    // ------------------------------------------------------------------

    @Test
    void repositoryInitRunsWhenMissing() throws BackupException {
        // repositoryExists() probes → throws → returns false
        doThrow(new BackupException("repo missing"))
                .when(shell).run(eq("restic"), eq("snapshots"), any(), any(), any(), any());

        service.repositoryInit();

        // restic init must be called
        verify(shell).run(eq("restic"), eq("init"), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Test 5: backupAppendsDryRunFlag
    // ------------------------------------------------------------------

    @Test
    void backupAppendsDryRunFlag() throws BackupException {
        when(config.isDryRun()).thenReturn(true);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        service.backup(List.of(Path.of("/data")));
        verify(shell).runStreaming(anyMap(), any(), captor.capture());

        List<String> args = Arrays.asList(captor.getValue());
        assertTrue(args.contains("--dry-run"),
                "--dry-run must be present in backup args when isDryRun=true, got: " + args);
        assertTrue(args.contains("--json"), "Missing --json");
        assertFalse(args.contains("--verbose=2"), "--verbose=2 must be removed");
        assertEquals("nextcloud", args.get(args.indexOf("--tag") + 1));
        assertEquals("host,tags", args.get(args.indexOf("--group-by") + 1));
    }

    // ------------------------------------------------------------------
    // Test 6: backupDoesNotAppendDryRunFlagInNormalMode
    // ------------------------------------------------------------------

    @Test
    void backupDoesNotAppendDryRunFlagInNormalMode() throws BackupException {
        when(config.isDryRun()).thenReturn(false);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        service.backup(List.of(Path.of("/data")));
        verify(shell).runStreaming(anyMap(), any(), captor.capture());

        List<String> args = Arrays.asList(captor.getValue());
        assertFalse(args.contains("--dry-run"),
                "--dry-run must NOT be present in normal mode, got: " + args);
    }

    // ------------------------------------------------------------------
    // Test 7: applyRetentionPassesCorrectKeepArgs
    // ------------------------------------------------------------------

    @Test
    void applyRetentionPassesCorrectKeepArgs() throws BackupException {
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        service.applyRetention();
        verify(shell).runStreaming(anyMap(), any(), captor.capture());

        List<String> args = Arrays.asList(captor.getValue());
        assertTrue(args.contains("--keep-daily"),   "Missing --keep-daily");
        assertTrue(args.contains("7"),               "Missing daily count");
        assertTrue(args.contains("--keep-weekly"),  "Missing --keep-weekly");
        assertTrue(args.contains("4"),               "Missing weekly count");
        assertTrue(args.contains("--keep-monthly"), "Missing --keep-monthly");
        assertTrue(args.contains("12"),              "Missing monthly count");
        assertFalse(args.contains("--verbose=2"),    "--verbose=2 must be removed");
        assertEquals("nextcloud", args.get(args.indexOf("--tag") + 1));
        assertEquals("host,tags", args.get(args.indexOf("--group-by") + 1));
    }

    // ------------------------------------------------------------------
    // Test 8: getLatestSnapshotIdParsesJson
    // ------------------------------------------------------------------

    @Test
    void getLatestSnapshotIdParsesJson() throws BackupException {
        String json = "[{\"short_id\":\"abc1234\",\"id\":\"abc1234abcdef\"}]";
        when(shell.runCapture(eq("restic"), eq("snapshots"), eq("--json"), eq("--last"),
                eq("--tag"), eq("nextcloud"),
                eq("--group-by"), eq("host,tags"),
                any(), any(), any(), any()))
                .thenReturn(json);

        String id = service.getLatestSnapshotId();
        assertEquals("abc1234", id);
    }

    @Test
    void getStatsFiltersLatestSnapshotByTag() throws BackupException {
        service.getStats();

        verify(shell).runCapture(
                "restic", "stats", "latest",
                "--tag", "nextcloud",
                "--repo", config.getResticRepository().toString(),
                "--password-file", config.getResticPasswordFile().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void backupSetsConfiguredProgressFrequency() throws BackupException {
        ArgumentCaptor<Map<String, String>> environment = ArgumentCaptor.forClass(Map.class);

        service.backup(List.of(Path.of("/data")));

        verify(shell).runStreaming(environment.capture(), any(Consumer.class), any(String[].class));
        assertEquals("0.033333", environment.getValue().get("RESTIC_PROGRESS_FPS"));
    }
}
