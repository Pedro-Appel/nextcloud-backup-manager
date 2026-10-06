package dev.irattiz.backup;

import dev.irattiz.backup.context.BackupContext;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.service.*;
import org.slf4j.Logger;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class BackupApplication {

    private static final Logger log = BackupLogger.getLogger(BackupApplication.class);

    public static void main(String[] args) {
        if (processArguments(args)) return;

        Path projectRoot;
        try {
            projectRoot = resolveProjectRoot();
        } catch (URISyntaxException e) {
            System.err.println("FATAL: cannot resolve project root — " + e.getMessage());
            System.exit(1);
            return;
        }

        BackupContext context;
        try {
            context = new BackupContext(projectRoot);
        } catch (BackupException e) {
            System.err.println("FATAL: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            runWorkflow(context);
        } catch (BackupException e) {
            log.error("Backup failed: {}", e.getMessage(), e);
            try {
                context.getNotifier().sendFailure(e.getMessage(), null, Duration.ZERO);
            } catch (Exception ignored) {
                // best-effort; don't mask the original failure
            }
            System.exit(1);
        }
    }

    /**
     * Executes the full backup workflow.
     * Package-private so BackupApplicationTest can call it directly with a mocked context.
     */
    static void runWorkflow(BackupContext context) throws BackupException {
        Instant startTime = Instant.now();

        BackupLogger.logBanner();

        DriveService            drive           = context.getDrive();
        NextcloudService        nextcloud       = context.getNextcloud();
        NextcloudBackupService  nextcloudBackup = context.getNextcloudBackup();
        ResticService           restic          = context.getRestic();
        NotifierService         notifier        = context.getNotifier();

        // ---- START notification ----
        Instant phaseStart = Instant.now();
        BackupLogger.logSection("Notification");
        notifier.sendStart();
        BackupLogger.logPhaseEnd("Notification", phaseStart);

        // Register the cleanup hook after the START notification, matching the legacy workflow.
        Runtime.getRuntime().addShutdownHook(
                new Thread(nextcloud::cleanup, "nextcloud-cleanup-hook"));

        // ---- Environment validation ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Environment Validation");
        nextcloud.check();
        drive.validate();
        BackupLogger.logPhaseEnd("Environment Validation", phaseStart);

        // ---- Consistency boundary: enable maintenance mode ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Consistency boundary");
        nextcloud.maintenanceEnable();
        BackupLogger.logPhaseEnd("Consistency boundary", phaseStart);

        // ---- Database export ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Database export (Snap)");
        nextcloudBackup.exportDb();
        BackupLogger.logPhaseEnd("Database export (Snap)", phaseStart);

        // ---- Restic backup ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Restic backup");
        restic.repositoryInit();
        restic.unlock();
        restic.backup(List.of(
                context.getConfig().getNextcloudDataDir(),
                context.getConfig().getNextcloudConfigDir(),
                nextcloudBackup.getExportPath()
        ));
        BackupLogger.logPhaseEnd("Restic backup", phaseStart);

        // ---- Retention policy ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Retention policy");
        restic.applyRetention();
        BackupLogger.logPhaseEnd("Retention policy", phaseStart);

        // ---- Cleanup Snap exports ----
        phaseStart = Instant.now();
        BackupLogger.logSection("Cleanup Snap exports");
        nextcloudBackup.cleanupExports();
        BackupLogger.logPhaseEnd("Cleanup Snap exports", phaseStart);

        // ---- End consistency boundary: disable maintenance mode ----
        phaseStart = Instant.now();
        BackupLogger.logSection("End consistency boundary");
        nextcloud.maintenanceDisable();
        BackupLogger.logPhaseEnd("End consistency boundary", phaseStart);

        // ---- Success notification + summary ----
        Duration totalDuration = Duration.between(startTime, Instant.now());
        String snapshotId = restic.getLatestSnapshotId();
        notifier.sendSuccess(snapshotId, totalDuration);

        BackupLogger.logSummary(snapshotId, totalDuration, hostname());

        restic.getStats();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static void printUsage() {
        System.out.println("Usage: nextcloud-backup-manager [--dry-run] [--help]");
        System.out.println("  --dry-run   Simulate all operations without writing data");
        System.out.println("  --help      Show this message");
    }

    static boolean processArguments(String[] args) {
        for (String arg : args) {
            if ("--dry-run".equals(arg)) System.setProperty("DRY_RUN", "true");
            if ("--help".equals(arg)) {
                printUsage();
                return true;
            }
        }
        return false;
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            log.warn("Could not resolve host name: {}", e.getMessage());
            return java.net.InetAddress.getLoopbackAddress().getHostName();
        }
    }

    /**
     * Derives the project root from the location of this JAR.
     * - Build JAR:  project/build/libs/app-all.jar → project root
     * - Installed JAR: /opt/backup/app.jar → /opt/backup
     * - IDE/test:  walks up until a directory containing config/ is found
     */
    static Path resolveProjectRoot() throws URISyntaxException {
        Path codeLocation = Path.of(
                BackupApplication.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());

        if (codeLocation.toString().endsWith(".jar")) {
            Path jarDirectory = codeLocation.getParent();
            Path parent = jarDirectory != null ? jarDirectory.getParent() : null;
            if (jarDirectory != null
                    && jarDirectory.getFileName() != null
                    && "libs".equals(jarDirectory.getFileName().toString())
                    && parent != null
                    && parent.getFileName() != null
                    && "build".equals(parent.getFileName().toString())
                    && parent.getParent() != null) {
                return parent.getParent();
            }
            return jarDirectory != null ? jarDirectory : Path.of(System.getProperty("user.dir"));
        }

        Path candidate = codeLocation.toAbsolutePath().normalize();
        while (candidate != null) {
            if (candidate.resolve("config").toFile().isDirectory()) {
                return candidate;
            }
            candidate = candidate.getParent();
        }

        return Path.of(System.getProperty("user.dir"));
    }
}
