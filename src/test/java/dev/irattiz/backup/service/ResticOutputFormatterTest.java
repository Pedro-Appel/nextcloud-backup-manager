package dev.irattiz.backup.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResticOutputFormatterTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private ResticOutputFormatter formatter;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(ResticOutputFormatter.class);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);

        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);

        formatter = new ResticOutputFormatter();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setAdditive(true);
    }

    @Test
    void formatsProgressAndSuppressesExactDuplicates() {
        String status = """
                {"message_type":"status","percent_done":0.423,"files_done":182441,
                 "total_files":430992,"bytes_done":341449900032,"total_bytes":807453851648,
                 "seconds_elapsed":1680,"seconds_remaining":2220,"error_count":0}
                """;

        formatter.accept(status);
        formatter.accept(status);

        List<String> messages = messagesAt(Level.INFO);
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("42.3%"));
        assertTrue(messages.getFirst().contains("ETA 37m 0s"));
    }

    @Test
    void formatsScanningStatusWithoutPercentage() {
        formatter.accept("""
                {"message_type":"status","total_files":128420,"total_bytes":690415255552,
                 "seconds_elapsed":75,"error_count":0}
                """);

        assertTrue(messagesAt(Level.INFO).getFirst().contains("Restic scanning:"));
    }

    @Test
    void capturesSnapshotIdAndFormatsSummary() {
        formatter.accept("""
                {"message_type":"summary","total_files_processed":430992,
                 "total_bytes_processed":807453851648,"data_added_packed":1073741824,
                 "total_duration":3912.4,"snapshot_id":"abc1234"}
                """);

        assertEquals("abc1234", formatter.result().snapshotId());
        assertTrue(messagesAt(Level.INFO).getFirst().contains("Restic summary:"));
        assertTrue(messagesAt(Level.INFO).getFirst().contains("snapshot abc1234"));
    }

    @Test
    void formatsStructuredErrors() {
        formatter.accept("""
                {"message_type":"error","during":"archival","item":"/data/file",
                 "error":{"message":"permission denied"}}
                """);

        assertTrue(messagesAt(Level.ERROR).getFirst().contains("permission denied"));
        assertTrue(messagesAt(Level.ERROR).getFirst().contains("/data/file"));
    }

    @Test
    void preservesMalformedOutput() {
        formatter.accept("repository opened successfully");

        assertTrue(messagesAt(Level.INFO).getFirst().contains("repository opened successfully"));
    }

    @Test
    void formatsBytesAndDurations() {
        assertEquals("1.0 GiB", ResticOutputFormatter.formatBytes(1_073_741_824));
        assertEquals("1h 2m", ResticOutputFormatter.formatDuration(3_725));
        assertEquals("12s", ResticOutputFormatter.formatDuration(12));
    }

    private List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
