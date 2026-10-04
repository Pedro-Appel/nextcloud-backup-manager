package dev.irattiz.backup.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupLoggerTest {

    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger backupLoggerLogger;

    /**
     * Attaches a ListAppender to the BackupLogger's own logger so we can
     * capture its output without writing to files or the console.
     */
    @BeforeEach
    void attachListAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        backupLoggerLogger = ctx.getLogger(BackupLogger.class);

        listAppender = new ListAppender<>();
        listAppender.setContext(ctx);
        listAppender.start();

        backupLoggerLogger.addAppender(listAppender);
        backupLoggerLogger.setAdditive(false); // prevent output leaking to root during tests
    }

    @AfterEach
    void detachListAppender() {
        backupLoggerLogger.detachAppender(listAppender);
        backupLoggerLogger.setAdditive(true);
    }

    private List<String> capturedMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ------------------------------------------------------------------
    // Test 1: logSectionFormatsCorrectly
    // ------------------------------------------------------------------

    @Test
    void logSectionFormatsCorrectly() {
        BackupLogger.logSection("Restic backup");

        List<String> messages = capturedMessages();
        assertTrue(
                messages.stream().anyMatch(m -> m.contains("=== Restic backup ===")),
                "Expected '=== Restic backup ===' in log output, got: " + messages
        );
    }

    // ------------------------------------------------------------------
    // Test 2: logPhaseEndIncludesDuration
    // ------------------------------------------------------------------

    @Test
    void logPhaseEndIncludesDuration() {
        Instant start = Instant.now().minusMillis(250);
        BackupLogger.logPhaseEnd("Drive validation", start);

        List<String> messages = capturedMessages();
        assertTrue(
                messages.stream().anyMatch(m ->
                        m.contains("[PHASE] Drive validation completed in") && m.contains("ms")),
                "Expected '[PHASE] Drive validation completed in <n>ms', got: " + messages
        );
    }

    // ------------------------------------------------------------------
    // Test 3: configureChangesLevel
    // ------------------------------------------------------------------

    @Test
    void configureChangesLevel() {
        BackupLogger.configure(Level.DEBUG, tempDir);

        org.slf4j.Logger slf4jLogger = BackupLogger.getLogger(BackupLoggerTest.class);
        // Cast to Logback logger to inspect the effective level
        Logger logbackLogger = (Logger) slf4jLogger;

        assertTrue(logbackLogger.isDebugEnabled(),
                "After configure(DEBUG, ...) the logger should have DEBUG enabled");
    }
}
