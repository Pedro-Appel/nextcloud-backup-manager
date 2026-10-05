package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Direct Snap mysqldump support, supplementary to the default export workflow. */
public class DatabaseService {
    private static final Logger log = BackupLogger.getLogger(DatabaseService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String MYSQLDUMP = "/snap/nextcloud/current/bin/mysqldump";

    private final BackupConfig config;
    private final ShellCommand shell;
    private Path lastDumpPath;

    public DatabaseService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.shell = shell;
    }

    public void dump() throws BackupException {
        String dbHost = readSetting("dbhost");
        String dbName = readSetting("dbname");
        String dbUser = readSetting("dbuser");
        String dbPassword = readSetting("dbpassword");

        List<String> command = new ArrayList<>(List.of(MYSQLDUMP, "--single-transaction", "--quick",
                "--lock-tables=false", "-u", dbUser, "-p" + dbPassword, dbName));
        if (dbHost.startsWith("/")) {
            command.add("--socket");
            command.add(dbHost);
        } else {
            command.add("-h");
            command.add(dbHost);
        }

        Path dumpDir = config.getProjectRoot().resolve("database");
        Path dumpPath = dumpDir.resolve(LocalDateTime.now().format(TIMESTAMP) + "-nextcloud.sql.gz");
        try {
            Files.createDirectories(dumpDir);
            try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(dumpPath))) {
                shell.runTo(gzip, command.toArray(String[]::new));
            }
            if (Files.size(dumpPath) <= 20) {
                Files.deleteIfExists(dumpPath);
                throw new BackupException("Database dump is empty");
            }
            lastDumpPath = dumpPath;
            log.info("Database dumped to {}", dumpPath);
        } catch (IOException e) {
            throw new BackupException("Could not write database dump: " + dumpPath, e);
        }
    }

    public Path getLastDumpPath() {
        if (lastDumpPath == null) throw new IllegalStateException("dump() has not been called yet");
        return lastDumpPath;
    }

    private String readSetting(String key) throws BackupException {
        return shell.runCapture(config.getNextcloudOcc().toString(), "config:system:get", key).strip();
    }
}
