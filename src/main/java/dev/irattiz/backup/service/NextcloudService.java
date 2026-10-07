package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import org.slf4j.Logger;

import java.nio.file.Files;

/**
 * Wraps nextcloud.occ calls and manages Nextcloud maintenance mode.
 * Maps 1-to-1 with lib/nextcloud.sh.
 *
 * All OCC calls go through an internal ShellCommand with the configured timeout.
 * cleanup() is safe to call from a JVM shutdown hook — it never throws.
 */
public class NextcloudService {

    private static final Logger log = BackupLogger.getLogger(NextcloudService.class);

    private final BackupConfig config;
    private final ShellCommand occShell; // shell with OCC timeout applied

    public NextcloudService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.occShell = shell.withTimeout(config.getOccTimeout());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Verifies that the nextcloud.occ binary is executable.
     *
     * @throws BackupException if the binary is missing or not executable
     */
    public void check() throws BackupException {
        if (config.isDryRun()) {
            log.info("[DRY-RUN] Skipping nextcloud.occ executable check");
            return;
        }
        if (!Files.isExecutable(config.getNextcloudOcc())) {
            throw new BackupException(
                    "nextcloud.occ is not executable: " + config.getNextcloudOcc());
        }
        log.debug("nextcloud.occ found: {}", config.getNextcloudOcc());
    }

    /**
     * Enables Nextcloud maintenance mode.
     */
    public void maintenanceEnable() throws BackupException {
        log.info("Enabling Nextcloud maintenance mode");
        occShell.run(occ(), "maintenance:mode", "--on");
    }

    /**
     * Disables Nextcloud maintenance mode.
     */
    public void maintenanceDisable() throws BackupException {
        log.info("Disabling Nextcloud maintenance mode");
        occShell.run(occ(), "maintenance:mode", "--off");
    }

    /**
     * Returns true if Nextcloud is currently in maintenance mode.
     */
    public boolean isMaintenance() throws BackupException {
        String output = occShell.runCapture(occ(), "maintenance:mode");
        return output.contains("enabled");
    }

    /**
     * Cleanup handler — disables maintenance mode if currently active.
     * Safe to call from a JVM shutdown hook: catches and logs all exceptions
     * without re-throwing.
     */
    public void cleanup() {
        try {
            if (isMaintenance()) {
                log.info("Cleanup: disabling Nextcloud maintenance mode");
                maintenanceDisable();
            }
        } catch (Exception e) {
            log.warn("Cleanup: failed to check/disable maintenance mode: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String occ() {
        return config.getNextcloudOcc().toString();
    }
}
