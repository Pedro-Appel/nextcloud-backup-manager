package dev.irattiz.backup.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackupExceptionTest {

    @Test
    void hasCorrectMessage() {
        BackupException ex = new BackupException("something went wrong");
        assertEquals("something went wrong", ex.getMessage());
    }

    @Test
    void wrapsCause() {
        Throwable cause = new RuntimeException("root cause");
        BackupException ex = new BackupException("wrapper message", cause);
        assertEquals("wrapper message", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
