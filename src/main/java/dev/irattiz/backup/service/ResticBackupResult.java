package dev.irattiz.backup.service;

/** Result metadata collected from Restic's backup summary message. */
public record ResticBackupResult(String snapshotId) {

    public static ResticBackupResult empty() {
        return new ResticBackupResult(null);
    }

    public boolean hasSnapshotId() {
        return snapshotId != null && !snapshotId.isBlank();
    }
}
