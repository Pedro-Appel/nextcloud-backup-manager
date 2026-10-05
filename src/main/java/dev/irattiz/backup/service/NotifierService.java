package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps the home-lab-notifier.jar subprocess.
 * Maps 1-to-1 with lib/notifier.sh.
 *
 * In dry-run mode every send method logs the would-be call and returns.
 * sendFailure is safe to call with null snapshotId / zero duration.
 */
public class NotifierService {

    private static final Logger log = BackupLogger.getLogger(NotifierService.class);

    private final BackupConfig config;
    private final ShellCommand shell;

    public NotifierService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.shell = shell;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Fires the START event. */
    public void sendStart() throws BackupException {
        if (config.isDryRun()) {
            log.info("[DRY-RUN] Would send notifier event: START");
            return;
        }
        validate();
        shell.run(buildArgs("START", null, null).toArray(new String[0]));
    }

    /** Fires the SUCCESS event with snapshot ID and duration. */
    public void sendSuccess(String snapshotId, Duration duration) throws BackupException {
        if (config.isDryRun()) {
            log.info("[DRY-RUN] Would send notifier event: SUCCESS snapshot={} duration={}s",
                    snapshotId, duration.toSeconds());
            return;
        }
        validate();
        List<String> args = buildArgs("SUCCESS", snapshotId, duration);
        shell.run(args.toArray(new String[0]));
    }

    /**
     * Fires the FAILURE event.
     * {@code snapshotId} and {@code duration} may be null / zero for early failures
     * — those args are omitted from the command when null.
     */
    public void sendFailure(String message, String snapshotId, Duration duration)
            throws BackupException {
        if (config.isDryRun()) {
            log.info("[DRY-RUN] Would send notifier event: FAILURE message={}", message);
            return;
        }
        validate();
        List<String> args = buildArgs("FAILURE", snapshotId, duration);
        args.add("--message");
        args.add(message);
        shell.run(args.toArray(new String[0]));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void validate() throws BackupException {
        Path jar = jarPath();
        if (!Files.exists(jar)) {
            throw new BackupException("Notifier JAR not found: " + jar);
        }
    }

    /**
     * Builds the base arg list for a notifier invocation.
     * Appends --snapshot and --duration only when non-null / non-zero.
     */
    private List<String> buildArgs(String event, String snapshotId, Duration duration) {
        List<String> args = new ArrayList<>();
        args.add("java");
        args.add("-jar");
        args.add(jarPath().toString());
        args.add("--event");
        args.add(event);
        if (snapshotId != null) {
            args.add("--snapshot");
            args.add(snapshotId);
        }
        if (duration != null && !duration.isZero()) {
            args.add("--duration");
            args.add(String.valueOf(duration.toSeconds()));
        }
        return args;
    }

    private Path jarPath() {
        return config.getNotifierDir().resolve("home-lab-notifier.jar");
    }
}
