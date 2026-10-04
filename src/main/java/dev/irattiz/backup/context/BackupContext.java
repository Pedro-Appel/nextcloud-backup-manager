package dev.irattiz.backup.context;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;

import java.nio.file.Path;

/**
 * Wires together the configuration and foundational utilities.
 * Services are added in Task 12 (Phase 3); this skeleton provides
 * the shared context that every service depends on.
 */
public class BackupContext {

    private final Path projectRoot;
    private final BackupConfig config;
    private final ShellCommand shell;

    /**
     * Constructs the context by loading config and initialising the logger.
     *
     * @param projectRoot root directory of the project (parent of config/, log/, etc.)
     * @throws BackupException if config is missing or invalid
     */
    public BackupContext(Path projectRoot) throws BackupException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();

        Path configFile = this.projectRoot.resolve("config/backup.conf");
        this.config = new BackupConfig(configFile);

        BackupLogger.configure(config.getLogLevel(), this.projectRoot.resolve("log"));

        this.shell = new ShellCommand(config.isDryRun());
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public BackupConfig getConfig() {
        return config;
    }

    public ShellCommand getShell() {
        return shell;
    }
}
