package dev.irattiz.backup.config;

import ch.qos.logback.classic.Level;
import dev.irattiz.backup.exception.BackupException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Loads and validates config/backup.conf.
 * Provides typed getters for all configuration values.
 * Fails fast on construction if required keys are missing or invalid.
 */
public class BackupConfig {

    // Snap defaults applied when the user has not set these keys
    private static final String SNAP_DEFAULT_DATA_DIR    = "/var/snap/nextcloud/current/nextcloud/data";
    private static final String SNAP_DEFAULT_CONFIG_DIR  = "/var/snap/nextcloud/current/nextcloud/config";
    private static final String SNAP_DEFAULT_BACKUP_DIR  = "/var/snap/nextcloud/common/backups";

    private static final List<String> REQUIRED_KEYS = List.of(
            "BACKUP_MOUNT",
            "BACKUP_DEVICE_UUID",
            "RESTIC_REPOSITORY",
            "RESTIC_PASSWORD_FILE",
            "NEXTCLOUD_OCC",
            "NOTIFIER_DIR"
    );

    private static final List<String> RETENTION_KEYS = List.of(
            "RESTIC_RETENTION_DAILY",
            "RESTIC_RETENTION_WEEKLY",
            "RESTIC_RETENTION_MONTHLY"
    );

    private final Properties props;
    private final Function<String, String> envSupplier;
    private final Path configFile;

    /**
     * Constructs a BackupConfig by loading the given properties file.
     * Uses {@link System#getenv} for environment variable lookups.
     *
     * @param configFile path to backup.conf
     * @throws BackupException if the file cannot be read or validation fails
     */
    public BackupConfig(Path configFile) throws BackupException {
        this(configFile, System::getenv);
    }

    /**
     * Constructs a BackupConfig with an injectable environment supplier.
     * Intended for testing — pass a lambda that provides test env vars.
     *
     * @param configFile  path to backup.conf
     * @param envSupplier function that maps env var name → value (or null)
     * @throws BackupException if the file cannot be read or validation fails
     */
    public BackupConfig(Path configFile, Function<String, String> envSupplier) throws BackupException {
        this.envSupplier = envSupplier;
        this.configFile = configFile.toAbsolutePath().normalize();
        this.props = new Properties();
        load(configFile);
        applySnapDefaults();
        validate();
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private void load(Path configFile) throws BackupException {
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new BackupException("Cannot read config file: " + configFile, e);
        }
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void applySnapDefaults() {
        if (props.getProperty("NEXTCLOUD_DATA_DIR") == null) {
            props.setProperty("NEXTCLOUD_DATA_DIR", SNAP_DEFAULT_DATA_DIR);
        }
        if (props.getProperty("NEXTCLOUD_CONFIG_DIR") == null) {
            props.setProperty("NEXTCLOUD_CONFIG_DIR", SNAP_DEFAULT_CONFIG_DIR);
        }
        if (props.getProperty("NEXTCLOUD_BACKUP_DIR") == null) {
            props.setProperty("NEXTCLOUD_BACKUP_DIR", SNAP_DEFAULT_BACKUP_DIR);
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validate() throws BackupException {
        // Required keys must be present and non-empty
        for (String key : REQUIRED_KEYS) {
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new BackupException("Required config key is missing: " + key);
            }
        }

        // Path-type keys must be absolute
        for (String key : props.stringPropertyNames()) {
            if (isPathKey(key)) {
                String value = props.getProperty(key);
                if (value != null && !value.isBlank() && !value.startsWith("/")) {
                    throw new BackupException(
                            "Config key '" + key + "' must be an absolute path (got: " + value + ")");
                }
            }
        }

        // Retention keys must be positive integers
        for (String key : RETENTION_KEYS) {
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) {
                continue; // will be caught by required-key check if it's also required
            }
            try {
                int n = Integer.parseInt(value.trim());
                if (n <= 0) {
                    throw new BackupException(
                            "Config key '" + key + "' must be a positive integer (got: " + value + ")");
                }
            } catch (NumberFormatException e) {
                throw new BackupException(
                        "Config key '" + key + "' must be a positive integer (got: " + value + ")");
            }
        }
    }

    private boolean isPathKey(String key) {
        return key.endsWith("_DIR")
                || key.endsWith("_FILE")
                || key.endsWith("_REPOSITORY")
                || key.endsWith("_MOUNT");
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    public Path getBackupMount() {
        return Path.of(props.getProperty("BACKUP_MOUNT"));
    }

    public Path getProjectRoot() {
        Path configDir = configFile.getParent();
        return configDir != null && configDir.getFileName() != null
                && configDir.getFileName().toString().equals("config")
                ? configDir.getParent() : configDir;
    }

    public String getBackupDeviceUuid() {
        return props.getProperty("BACKUP_DEVICE_UUID");
    }

    public Path getResticRepository() {
        return Path.of(props.getProperty("RESTIC_REPOSITORY"));
    }

    public Path getResticPasswordFile() {
        return Path.of(props.getProperty("RESTIC_PASSWORD_FILE"));
    }

    public Path getResticCacheDir() {
        String v = props.getProperty("RESTIC_CACHE_DIR");
        return v != null ? Path.of(v) : null;
    }

    public int getResticRetentionDaily() {
        return Integer.parseInt(props.getProperty("RESTIC_RETENTION_DAILY", "7").trim());
    }

    public int getResticRetentionWeekly() {
        return Integer.parseInt(props.getProperty("RESTIC_RETENTION_WEEKLY", "4").trim());
    }

    public int getResticRetentionMonthly() {
        return Integer.parseInt(props.getProperty("RESTIC_RETENTION_MONTHLY", "12").trim());
    }

    public Path getNextcloudDataDir() {
        return Path.of(props.getProperty("NEXTCLOUD_DATA_DIR"));
    }

    public Path getNextcloudConfigDir() {
        return Path.of(props.getProperty("NEXTCLOUD_CONFIG_DIR"));
    }

    public Path getNextcloudBackupDir() {
        return Path.of(props.getProperty("NEXTCLOUD_BACKUP_DIR"));
    }

    public Path getNextcloudOcc() {
        return Path.of(props.getProperty("NEXTCLOUD_OCC"));
    }

    public Path getNotifierDir() {
        return Path.of(props.getProperty("NOTIFIER_DIR"));
    }

    /**
     * Returns the OCC timeout. Reads NEXTCLOUD_OCC_TIMEOUT from config (seconds).
     * Defaults to 30 seconds if not set or blank.
     */
    public Duration getOccTimeout() {
        String v = props.getProperty("NEXTCLOUD_OCC_TIMEOUT");
        if (v == null || v.isBlank()) {
            return Duration.ofSeconds(30);
        }
        try {
            return Duration.ofSeconds(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(30);
        }
    }

    /**
     * Returns the Logback log level from LOG_LEVEL config key.
     * Defaults to INFO if not set or unrecognised.
     */
    public Level getLogLevel() {
        String v = props.getProperty("LOG_LEVEL", "INFO");
        Level level = Level.toLevel(v, Level.INFO);
        return level;
    }

    /**
     * DRY_RUN precedence: environment variable → system property → config file → false.
     */
    public boolean isDryRun() {
        // 1. Environment variable
        String envVal = envSupplier.apply("DRY_RUN");
        if (envVal != null) {
            return "true".equalsIgnoreCase(envVal.trim());
        }
        // 2. System property (set by --dry-run CLI flag in Task 17)
        String sysProp = System.getProperty("DRY_RUN");
        if (sysProp != null) {
            return "true".equalsIgnoreCase(sysProp.trim());
        }
        // 3. Config file
        String configVal = props.getProperty("DRY_RUN");
        if (configVal != null) {
            return "true".equalsIgnoreCase(configVal.trim());
        }
        return false;
    }
}
