package dev.irattiz.backup.context;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.service.*;
import dev.irattiz.backup.util.ShellCommand;

import java.nio.file.Path;

/**
 * Wires configuration and all services together.
 * Acts as the application's composition root — no DI framework needed.
 * BackupApplication holds one instance of this and delegates all work through it.
 */
public class BackupContext {

    private final Path projectRoot;
    private final BackupConfig config;
    private final ShellCommand shell;

    private final DriveService drive;
    private final NextcloudService nextcloud;
    private final NextcloudBackupService nextcloudBackup;
    private final ResticService restic;
    private final NotifierService notifier;

    /**
     * Constructs the full context: loads config, configures logging,
     * instantiates all services, and runs their init checks.
     *
     * @param projectRoot root directory of the project (parent of config/, log/, etc.)
     * @throws BackupException if config is missing/invalid or any service init fails
     */
    public BackupContext(Path projectRoot) throws BackupException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();

        Path configFile = this.projectRoot.resolve("config/backup.conf");
        this.config = new BackupConfig(configFile);

        BackupLogger.configure(config.getLogLevel(), this.projectRoot.resolve("log"));

        this.shell = new ShellCommand(config.isDryRun());

        // Construct services in dependency order
        this.drive           = new DriveService(config, shell);
        this.nextcloud       = new NextcloudService(config, shell);
        this.nextcloudBackup = new NextcloudBackupService(config, shell);
        this.restic          = new ResticService(config, shell);
        this.notifier        = new NotifierService(config, shell);

        // Run init checks (equivalent to common_init in Bash)
        this.restic.init();
        this.nextcloudBackup.init();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public Path projectRoot()                    { return projectRoot; }
    public BackupConfig getConfig()              { return config; }
    public ShellCommand getShell()               { return shell; }
    public DriveService getDrive()               { return drive; }
    public NextcloudService getNextcloud()       { return nextcloud; }
    public NextcloudBackupService getNextcloudBackup() { return nextcloudBackup; }
    public ResticService getRestic()             { return restic; }
    public NotifierService getNotifier()         { return notifier; }
}
