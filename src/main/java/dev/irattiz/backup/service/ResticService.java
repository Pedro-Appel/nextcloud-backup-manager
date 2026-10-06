package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonReader;
import org.slf4j.Logger;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the restic CLI.
 * Maps 1-to-1 with lib/restic.sh.
 */
public class ResticService {

    private static final Logger log = BackupLogger.getLogger(ResticService.class);

    private final BackupConfig config;
    private final ShellCommand shell;

    public ResticService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.shell = shell;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Verifies prerequisites: restic binary on PATH, password file exists.
     */
    public void init() throws BackupException {
        // Verify restic is on PATH
        try {
            shell.runCapture("which", "restic");
        } catch (BackupException e) {
            throw new BackupException("restic binary not found on PATH", e);
        }

        // Verify password file exists
        Path passwordFile = config.getResticPasswordFile();
        if (!Files.exists(passwordFile)) {
            throw new BackupException("Restic password file not found: " + passwordFile);
        }

        log.debug("Restic prerequisites OK — repo: {}", config.getResticRepository());
    }

    /**
     * Returns true if the Restic repository already exists (accessible).
     */
    public boolean repositoryExists() throws BackupException {
        try {
            shell.run("restic", "snapshots",
                    "--repo", config.getResticRepository().toString(),
                    "--password-file", config.getResticPasswordFile().toString());
            return true;
        } catch (BackupException e) {
            return false;
        }
    }

    /**
     * Initialises the Restic repository if it does not already exist.
     */
    public void repositoryInit() throws BackupException {
        if (!repositoryExists()) {
            log.info("Initialising Restic repository at {}", config.getResticRepository());
            shell.run("restic", "init",
                    "--repo", config.getResticRepository().toString(),
                    "--password-file", config.getResticPasswordFile().toString());
        } else {
            log.debug("Restic repository already exists — skipping init");
        }
    }

    /**
     * Removes any stale Restic locks.
     */
    public void unlock() throws BackupException {
        log.info("Unlocking Restic repository");
        shell.run("restic", "unlock",
                "--repo", config.getResticRepository().toString(),
                "--password-file", config.getResticPasswordFile().toString());
    }

    /**
     * Runs a Restic backup of the given paths.
     * Appends {@code --dry-run} when dry-run mode is active.
     */
    public void backup(List<Path> paths) throws BackupException {
        List<String> args = new ArrayList<>();
        args.add("restic");
        args.add("backup");
        args.add("--verbose=2");
        args.add("--tag");
        args.add(config.getResticTag());
        args.add("--group-by");
        args.add("host,tags");
        args.add("--repo");
        args.add(config.getResticRepository().toString());
        args.add("--password-file");
        args.add(config.getResticPasswordFile().toString());
        for (Path p : paths) {
            args.add(p.toString());
        }
        if (config.isDryRun()) {
            args.add("--dry-run");
        }
        log.info("Running Restic backup of {} path(s)", paths.size());
        shell.runLogged(args.toArray(new String[0]));
    }

    /**
     * Applies the configured retention policy using {@code restic forget --prune}.
     */
    public void applyRetention() throws BackupException {
        log.info("Applying Restic retention policy");
        shell.runLogged(
                "restic", "forget", "--prune", "--verbose=2",
                "--repo", config.getResticRepository().toString(),
                "--password-file", config.getResticPasswordFile().toString(),
                "--tag", config.getResticTag(),
                "--group-by", "host,tags",
                "--keep-daily",   String.valueOf(config.getResticRetentionDaily()),
                "--keep-weekly",  String.valueOf(config.getResticRetentionWeekly()),
                "--keep-monthly", String.valueOf(config.getResticRetentionMonthly())
        );
    }

    /**
     * Returns the short ID of the most recent Restic snapshot by parsing
     * {@code restic snapshots --json --last} output.
     */
    public String getLatestSnapshotId() throws BackupException {
        String json = shell.runCapture(
                "restic", "snapshots", "--json", "--last",
                "--tag", config.getResticTag(),
                "--group-by", "host,tags",
                "--repo", config.getResticRepository().toString(),
                "--password-file", config.getResticPasswordFile().toString());

        if (json == null || json.isBlank()) {
            return "(no snapshot)";
        }

        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonArray array = reader.readArray();
            if (array.isEmpty()) {
                return "(no snapshot)";
            }
            return array.getJsonObject(0).getString("short_id");
        } catch (Exception e) {
            log.warn("Could not parse snapshot JSON: {}", e.getMessage());
            return "(unknown)";
        }
    }

    /**
     * Logs Restic repository statistics at INFO level.
     */
    public void getStats() throws BackupException {
        log.info("Restic repository stats:");
        String stats = shell.runCapture(
                "restic", "stats", "latest",
                "--tag", config.getResticTag(),
                "--repo", config.getResticRepository().toString(),
                "--password-file", config.getResticPasswordFile().toString());
        log.info(stats);
    }
}
