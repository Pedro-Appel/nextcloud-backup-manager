package dev.irattiz.backup.util;

import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reusable wrapper around {@link ProcessBuilder}.
 * <p>
 * No service class should instantiate {@link ProcessBuilder} directly;
 * all subprocess execution goes through this class. This keeps process
 * execution testable and makes dry-run behaviour consistent.
 * </p>
 *
 * <p>Usage:</p>
 * <pre>
 *   ShellCommand shell = new ShellCommand(config.isDryRun());
 *   shell.run("restic", "unlock");
 *   String out = shell.runCapture("blkid", "-U", uuid);
 *
 *   // With a timeout:
 *   shell.withTimeout(Duration.ofSeconds(30)).run("nextcloud.occ", "maintenance:mode", "--on");
 * </pre>
 */
public class ShellCommand {

    private static final Logger log = BackupLogger.getLogger(ShellCommand.class);

    private final boolean dryRun;
    private final Duration timeout; // null means no timeout

    /**
     * Creates a new ShellCommand.
     *
     * @param dryRun when {@code true} all commands are logged but not executed
     */
    public ShellCommand(boolean dryRun) {
        this(dryRun, null);
    }

    private ShellCommand(boolean dryRun, Duration timeout) {
        this.dryRun = dryRun;
        this.timeout = timeout;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a new {@code ShellCommand} that applies a wall-clock timeout to
     * every command. If the timeout is breached the process is destroyed and a
     * {@link BackupException} is thrown.
     *
     * @param timeout maximum time to wait for a command to complete
     * @return a new instance with the timeout set
     */
    public ShellCommand withTimeout(Duration timeout) {
        return new ShellCommand(this.dryRun, timeout);
    }

    /**
     * Executes a command, streaming its output to DEBUG.
     * Throws {@link BackupException} on non-zero exit code.
     * In dry-run mode, logs the command and returns immediately.
     *
     * @param args command and its arguments
     * @throws BackupException if the command exits non-zero or cannot be started
     */
    public void run(String... args) throws BackupException {
        if (dryRun) {
            log.info("[DRY-RUN] Would run: {}", String.join(" ", args));
            return;
        }
        execute(args, false, Map.of(), log::debug);
    }

    /**
     * Executes a command with additional environment variables and streams
     * each output line to the supplied handler.
     *
     * @param environment environment variables to add or override
     * @param outputHandler receives merged stdout and stderr one line at a time
     * @param args command and its arguments
     * @throws BackupException if the command exits non-zero or cannot be started
     */
    public void runStreaming(
            Map<String, String> environment,
            Consumer<String> outputHandler,
            String... args) throws BackupException {
        if (dryRun) {
            log.info("[DRY-RUN] Would run: {}", String.join(" ", args));
            return;
        }
        execute(args, false, environment, outputHandler);
    }

    /**
     * Executes a command and returns its stdout as a trimmed {@link String}.
     * Throws {@link BackupException} on non-zero exit code.
     * In dry-run mode, logs the command and returns an empty string.
     *
     * @param args command and its arguments
     * @return trimmed stdout of the command, or {@code ""} in dry-run mode
     * @throws BackupException if the command exits non-zero or cannot be started
     */
    public String runCapture(String... args) throws BackupException {
        if (dryRun) {
            log.info("[DRY-RUN] Would run: {}", String.join(" ", args));
            return "";
        }
        return (String) execute(args, true, Map.of(), null);
    }

    /** Streams command stdout directly into the supplied output stream. */
    public void runTo(OutputStream output, String... args) throws BackupException {
        String commandLine = redactSensitiveArgs(args);
        if (dryRun) {
            log.info("[DRY-RUN] Would run: {}", commandLine);
            return;
        }
        log.debug("Running: {}", commandLine);
        Process process;
        try {
            process = new ProcessBuilder(List.of(args)).start();
        } catch (IOException e) {
            throw new BackupException("Cannot start command: " + commandLine, e);
        }
        Thread errorDrainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) log.debug(line);
            } catch (IOException ignored) { }
        }, "shell-command-stderr");
        errorDrainer.setDaemon(true);
        errorDrainer.start();
        try {
            process.getInputStream().transferTo(output);
            int exitCode = waitFor(process, commandLine);
            errorDrainer.join(500);
            if (exitCode != 0) throw new BackupException("Command failed (exit " + exitCode + "): " + commandLine);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new BackupException("Failed streaming command output: " + commandLine, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BackupException("Command interrupted: " + commandLine, e);
        }
    }

    private String redactSensitiveArgs(String[] args) {
        return java.util.Arrays.stream(args)
                .map(arg -> arg.startsWith("-p") && arg.length() > 2 ? "-p******" : arg)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    // -------------------------------------------------------------------------
    // Internal execution
    // -------------------------------------------------------------------------

    /**
     * Executes the command. If {@code capture} is true, returns stdout as a
     * String; otherwise streams each line to {@code outputHandler} and returns
     * null.
     */
    private Object execute(
            String[] args,
            boolean capture,
            Map<String, String> environment,
            Consumer<String> outputHandler) throws BackupException {
        String commandLine = String.join(" ", args);
        log.debug("Running: {}", commandLine);

        ProcessBuilder pb = new ProcessBuilder(List.of(args));
        pb.redirectErrorStream(true); // merge stderr into stdout
        pb.environment().putAll(environment);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new BackupException("Cannot start command: " + commandLine, e);
        }

        StringBuilder output = new StringBuilder();

        // Drain stdout in a background thread so that waitFor() (with timeout) can
        // run concurrently. If we read synchronously first, the process can block
        // waiting for us to consume output while we are blocked in the read loop —
        // and the timeout never fires.
        Thread drainThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (capture) {
                        synchronized (output) {
                            if (!output.isEmpty()) {
                                output.append('\n');
                            }
                            output.append(line);
                        }
                    } else {
                        try {
                            outputHandler.accept(line);
                        } catch (RuntimeException e) {
                            log.warn("Command output handler failed: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process was killed (timeout) — stream close is expected
            }
        });
        drainThread.setDaemon(true);
        drainThread.start();

        // Wait for completion (with optional timeout)
        int exitCode = waitFor(process, commandLine);

        // Give the drain thread a moment to finish flushing buffered output
        try {
            drainThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (exitCode != 0) {
            throw new BackupException(
                    "Command failed (exit " + exitCode + "): " + commandLine);
        }

        return capture ? output.toString().trim() : null;
    }

    private int waitFor(Process process, String commandLine) throws BackupException {
        try {
            if (timeout != null) {
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new BackupException("Command timed out: " + commandLine);
                }
            } else {
                process.waitFor();
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BackupException("Command interrupted: " + commandLine, e);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (package-private for testing)
    // -------------------------------------------------------------------------

    boolean isDryRun() {
        return dryRun;
    }

    Duration getTimeout() {
        return timeout;
    }
}
