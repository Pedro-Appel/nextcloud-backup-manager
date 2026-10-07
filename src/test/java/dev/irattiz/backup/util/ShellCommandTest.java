package dev.irattiz.backup.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.irattiz.backup.exception.BackupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellCommandTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger shellLogger;

    @BeforeEach
    void attachListAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        shellLogger = ctx.getLogger(ShellCommand.class);
        shellLogger.setLevel(Level.DEBUG); // ensure DEBUG messages are captured

        listAppender = new ListAppender<>();
        listAppender.setContext(ctx);
        listAppender.start();

        shellLogger.addAppender(listAppender);
        shellLogger.setAdditive(false);
    }

    @AfterEach
    void detachListAppender() {
        shellLogger.detachAppender(listAppender);
        shellLogger.setAdditive(true);
    }

    private List<String> capturedMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ------------------------------------------------------------------
    // Test 1: runExecutesCommand
    // ------------------------------------------------------------------

    @Test
    void runExecutesCommand() {
        ShellCommand shell = new ShellCommand(false);
        assertDoesNotThrow(() -> shell.run("echo", "hello"));
    }

    // ------------------------------------------------------------------
    // Test 2: runThrowsOnNonZeroExit
    // ------------------------------------------------------------------

    @Test
    void runThrowsOnNonZeroExit() {
        ShellCommand shell = new ShellCommand(false);
        BackupException ex = assertThrows(BackupException.class, () -> shell.run("false"));
        assertTrue(ex.getMessage().contains("exit 1"),
                "Exception message should contain 'exit 1', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 3: runCaptureReturnsStdout
    // ------------------------------------------------------------------

    @Test
    void runCaptureReturnsStdout() throws BackupException {
        ShellCommand shell = new ShellCommand(false);
        String result = shell.runCapture("echo", "captured");
        assertEquals("captured", result);
    }

    // ------------------------------------------------------------------
    // Test 4: dryRunSkipsExecution
    // ------------------------------------------------------------------

    @Test
    void dryRunSkipsExecution() {
        ShellCommand shell = new ShellCommand(true);

        // "false" always exits 1 — but dry-run must not execute it
        assertDoesNotThrow(() -> shell.run("false"));

        // The [DRY-RUN] prefix must appear in the captured log output
        List<String> messages = capturedMessages();
        assertTrue(
                messages.stream().anyMatch(m -> m.contains("DRY-RUN")),
                "Expected a [DRY-RUN] log message, got: " + messages
        );
    }

    // ------------------------------------------------------------------
    // Test 5: withTimeoutThrowsOnBreach
    // ------------------------------------------------------------------

    @Test
    void withTimeoutThrowsOnBreach() {
        ShellCommand shell = new ShellCommand(false).withTimeout(Duration.ofMillis(100));
        BackupException ex = assertThrows(BackupException.class,
                () -> shell.run("sleep", "10"));
        assertTrue(ex.getMessage().toLowerCase().contains("timed out"),
                "Exception message should contain 'timed out', was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Test 6: commandIsLoggedAtDebug
    // ------------------------------------------------------------------

    @Test
    void commandIsLoggedAtDebug() throws BackupException {
        ShellCommand shell = new ShellCommand(false);
        shell.run("echo", "log-test");

        List<String> messages = capturedMessages();
        assertTrue(
                messages.stream().anyMatch(m -> m.contains("echo") && m.contains("log-test")),
                "Expected the full command to appear in DEBUG log, got: " + messages
        );
    }

    @Test
    void runStreamingPassesEnvironmentAndLinesToHandler() throws BackupException {
        ShellCommand shell = new ShellCommand(false);
        List<String> lines = new ArrayList<>();

        shell.runStreaming(
                Map.of("NBM_STREAM_TEST", "configured"),
                lines::add,
                "sh", "-c", "printf '%s\\n' \"$NBM_STREAM_TEST\"");

        assertEquals(List.of("configured"), lines);
    }
}
