package dev.irattiz.backup.exception;

/**
 * Single checked exception used throughout the backup application.
 * All service failures are wrapped in or thrown as this type, allowing
 * BackupApplication.main() to catch one type at the top level.
 */
public class BackupException extends Exception {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
