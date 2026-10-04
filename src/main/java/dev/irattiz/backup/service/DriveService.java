package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import dev.irattiz.backup.util.ShellCommand;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the external backup drive lifecycle.
 * Maps 1-to-1 with lib/drive.sh.
 */
public class DriveService {

    private static final Logger log = BackupLogger.getLogger(DriveService.class);

    /** Minimum free space required on the backup drive: 5 GiB in bytes. */
    static final long MIN_FREE_BYTES = 5_368_709_120L;

    private final BackupConfig config;
    private final ShellCommand shell;

    public DriveService(BackupConfig config, ShellCommand shell) {
        this.config = config;
        this.shell = shell;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if the backup mount point is currently mounted.
     */
    public boolean isMounted() {
        try {
            shell.run("mountpoint", "-q", config.getBackupMount().toString());
            return true;
        } catch (BackupException e) {
            return false;
        }
    }

    /**
     * Mounts the backup drive by UUID.
     * Resolves the device path via {@code blkid -U <uuid>} then calls {@code mount}.
     */
    public void mount() throws BackupException {
        String device = shell.runCapture("blkid", "-U", config.getBackupDeviceUuid()).strip();
        if (device.isBlank()) {
            throw new BackupException(
                    "Cannot resolve device for UUID: " + config.getBackupDeviceUuid());
        }
        log.info("Mounting {} at {}", device, config.getBackupMount());
        shell.run("mount", device, config.getBackupMount().toString());
    }

    /**
     * Verifies the backup mount is writable by creating and deleting a sentinel file.
     */
    public void checkWritable() throws BackupException {
        Path sentinel = config.getBackupMount().resolve(".backup-writable-check");
        try {
            Files.createFile(sentinel);
            Files.delete(sentinel);
            log.debug("Backup mount is writable: {}", config.getBackupMount());
        } catch (IOException e) {
            throw new BackupException(
                    "Backup mount is not writable: " + config.getBackupMount(), e);
        }
    }

    /**
     * Verifies at least {@value #MIN_FREE_BYTES} bytes are available on the backup mount.
     */
    public void checkSpace() throws BackupException {
        String dfOutput = shell.runCapture("df", "-k", config.getBackupMount().toString());

        // df -k output has a header line then one data line:
        // Filesystem     1K-blocks    Used Available Use% Mounted on
        // /dev/sdb1       20971520 1048576  19922944   6% /mnt/backup
        String[] lines = dfOutput.split("\n");
        if (lines.length < 2) {
            throw new BackupException("Unexpected df output: " + dfOutput);
        }
        String[] cols = lines[1].trim().split("\\s+");
        if (cols.length < 4) {
            throw new BackupException("Cannot parse df output: " + lines[1]);
        }
        long availableKb;
        try {
            availableKb = Long.parseLong(cols[3]);
        } catch (NumberFormatException e) {
            throw new BackupException("Cannot parse available space from df output: " + cols[3]);
        }
        long availableBytes = availableKb * 1024L;
        if (availableBytes < MIN_FREE_BYTES) {
            throw new BackupException(String.format(
                    "Insufficient space on backup drive: %.1f GiB available, need %.1f GiB",
                    availableBytes / 1_073_741_824.0,
                    MIN_FREE_BYTES / 1_073_741_824.0));
        }
        log.info("Backup drive space OK: {} GiB available",
                String.format("%.1f", availableBytes / 1_073_741_824.0));
    }

    /**
     * Full drive validation: mount if needed, check writable, check space.
     * Called from BackupApplication.
     */
    public void validate() throws BackupException {
        if (!isMounted()) {
            log.info("Backup drive not mounted — mounting now");
            mount();
        } else {
            log.info("Backup drive already mounted at {}", config.getBackupMount());
        }
        checkWritable();
        checkSpace();
    }
}
