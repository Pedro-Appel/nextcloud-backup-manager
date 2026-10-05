package dev.irattiz.backup.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.net.URL;

/**
 * Façade over SLF4J + Logback.
 * <p>
 * The rest of the codebase must use this class rather than importing Logback
 * types directly. That keeps the logging implementation swappable and the
 * Logback dependency contained to this package.
 * </p>
 */
public final class BackupLogger {

    private static final Logger log = LoggerFactory.getLogger(BackupLogger.class);

    private BackupLogger() {
        // static utility class
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Returns an SLF4J Logger for the given class.
     * Use this in every class that needs to log:
     * <pre>
     *   private static final Logger log = BackupLogger.getLogger(MyClass.class);
     * </pre>
     */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    // -------------------------------------------------------------------------
    // Runtime configuration
    // -------------------------------------------------------------------------

    /**
     * Sets the root logger level and injects the log directory into Logback's
     * context so the FILE appender can resolve {@code ${LOG_DIR}}.
     * Must be called early in startup, before any logging occurs.
     *
     * @param level  SLF4J/Logback level to use as the root minimum
     * @param logDir directory where daily log files will be written
     */
    public static void configure(Level level, Path logDir) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        try {
            URL config = BackupLogger.class.getClassLoader().getResource("logback.xml");
            if (config != null) {
                ctx.reset();
                ctx.putProperty("LOG_DIR", logDir.toAbsolutePath().toString());
                ctx.putProperty("LOG_LEVEL", level.toString());
                JoranConfigurator configurator = new JoranConfigurator();
                configurator.setContext(ctx);
                configurator.doConfigure(config);
            }
            ch.qos.logback.classic.Logger root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            root.setLevel(level);
        } catch (ch.qos.logback.core.joran.spi.JoranException e) {
            throw new IllegalStateException("Cannot configure logging", e);
        }
    }

    // -------------------------------------------------------------------------
    // Structured phase helpers
    // -------------------------------------------------------------------------

    /**
     * Logs the start of a named backup phase.
     * Output format: {@code === <name> ===}
     */
    public static void logSection(String name) {
        log.info("----------------------------------------------------------");
        log.info("=== {} ===", name);
    }

    /**
     * Logs the completion of a named phase with elapsed time.
     * Output format: {@code [PHASE] <name> completed in <ms>ms}
     *
     * @param name      the phase name (must match the name passed to {@link #logSection})
     * @param startedAt the {@link Instant} at which the phase started
     */
    public static void logPhaseEnd(String name, Instant startedAt) {
        long ms = Duration.between(startedAt, Instant.now()).toMillis();
        log.info("[PHASE] {} completed in {}ms", name, ms);
    }

    // -------------------------------------------------------------------------
    // Banner and summary
    // -------------------------------------------------------------------------

    /**
     * Prints the ASCII art startup banner.
     */
    public static void logBanner() {
        log.info(""" 
            
            ██████╗  █████╗  ██████╗██╗  ██╗██╗   ██╗██████╗              
            ██╔══██╗██╔══██╗██╔════╝██║ ██╔╝██║   ██║██╔══██╗             
            ██████╔╝███████║██║     █████╔╝ ██║   ██║██████╔╝             
            ██╔══██╗██╔══██║██║     ██╔═██╗ ██║   ██║██╔═══╝              
            ██████╔╝██║  ██║╚██████╗██║  ██╗╚██████╔╝██║                  
            ╚═════╝ ╚═╝  ╚═╝ ╚═════╝╚═╝  ╚═╝ ╚═════╝ ╚═╝                  
                                                                        
            ███╗   ███╗ █████╗ ███╗   ██╗ █████╗  ██████╗ ███████╗██████╗ 
            ████╗ ████║██╔══██╗████╗  ██║██╔══██╗██╔════╝ ██╔════╝██╔══██╗
            ██╔████╔██║███████║██╔██╗ ██║███████║██║  ███╗█████╗  ██████╔╝
            ██║╚██╔╝██║██╔══██║██║╚██╗██║██╔══██║██║   ██║██╔══╝  ██╔══██╗
            ██║ ╚═╝ ██║██║  ██║██║ ╚████║██║  ██║╚██████╔╝███████╗██║  ██║
            ╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝
            by CoffeeBabe 
    """);
    }

    /**
     * Logs the end-of-run summary.
     */
    public static void logSummary(String snapshotId, Duration duration, String hostname) {
        log.info("----------------------------------------------------------");
        log.info("  Snapshot : {}", snapshotId != null ? snapshotId : "(dry-run)");
        log.info("  Duration : {}", formatDuration(duration));
        log.info("  Host     : {}", hostname);
        log.info("  Finished : {}", java.time.LocalDateTime.now());
        log.info("----------------------------------------------------------");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    static String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
