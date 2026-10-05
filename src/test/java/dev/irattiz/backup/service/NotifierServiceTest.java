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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifierServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    BackupConfig config;

    @Mock
    ShellCommand shell;

    @BeforeEach
    void setUp() {
        when(config.getNotifierDir()).thenReturn(tempDir);
        when(config.isDryRun()).thenReturn(false);
    }

    private NotifierService service() {
        return new NotifierService(config, shell);
    }

    /** Creates the notifier JAR dummy file in tempDir. */
    private Path createJar() throws IOException {
        Path jar = tempDir.resolve("home-lab-notifier.jar");
        Files.createFile(jar);
        return jar;
    }

    // ------------------------------------------------------------------
    // Test 1: sendStartThrowsWhenJarMissing
    // ------------------------------------------------------------------

    @Test
    void sendStartThrowsWhenJarMissing() {
        // tempDir has no home-lab-notifier.jar
        BackupException ex = assertThrows(BackupException.class, () -> service().sendStart());
        assertTrue(ex.getMessage().toLowerCase().contains("notifier"),
                "Exception should mention 'notifier', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 2: sendStartRunsCorrectCommand
    // ------------------------------------------------------------------

    @Test
    void sendStartRunsCorrectCommand() throws Exception {
        createJar();
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);

        service().sendStart();

        verify(shell).run(captor.capture());
        List<String> args = Arrays.asList(captor.getValue());
        assertTrue(args.contains("--event"), "Args must contain --event");
        assertTrue(args.contains("START"),   "Args must contain START");
    }

    // ------------------------------------------------------------------
    // Test 3: sendSuccessIncludesSnapshotAndDuration
    // ------------------------------------------------------------------

    @Test
    void sendSuccessIncludesSnapshotAndDuration() throws Exception {
        createJar();
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);

        service().sendSuccess("abc1234", Duration.ofSeconds(90));

        verify(shell).run(captor.capture());
        List<String> args = Arrays.asList(captor.getValue());
        assertTrue(args.contains("SUCCESS"),     "Args must contain SUCCESS");
        assertTrue(args.contains("--snapshot"),  "Args must contain --snapshot");
        assertTrue(args.contains("abc1234"),     "Args must contain the snapshot ID");
        assertTrue(args.contains("--duration"),  "Args must contain --duration");
        assertTrue(args.contains("90"),          "Args must contain the duration in seconds");
    }

    // ------------------------------------------------------------------
    // Test 4: sendFailureOmitsSnapshotWhenNull
    // ------------------------------------------------------------------

    @Test
    void sendFailureOmitsSnapshotWhenNull() throws Exception {
        createJar();
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);

        service().sendFailure("backup failed", null, Duration.ZERO);

        verify(shell).run(captor.capture());
        List<String> args = Arrays.asList(captor.getValue());
        assertTrue(args.contains("FAILURE"),        "Args must contain FAILURE");
        assertFalse(args.contains("--snapshot"),    "--snapshot must NOT appear when snapshotId is null");
        assertFalse(args.contains("--duration"),    "--duration must NOT appear when duration is zero");
        assertTrue(args.contains("--message"),      "Args must contain --message");
        assertTrue(args.contains("backup failed"),  "Args must contain the message text");
    }

    // ------------------------------------------------------------------
    // Test 5: dryRunSkipsSendStart
    // ------------------------------------------------------------------

    @Test
    void dryRunSkipsSendStart() throws Exception {
        createJar();
        when(config.isDryRun()).thenReturn(true);

        service().sendStart();

        // Shell must never be called in dry-run
        verify(shell, never()).run(any(String[].class));
    }
}
