package dev.irattiz.backup.service;

import dev.irattiz.backup.config.BackupConfig;
import dev.irattiz.backup.util.ShellCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseServiceTest {
    @TempDir Path tempDir;
    BackupConfig config;
    ShellCommand shell;
    DatabaseService service;
    java.util.List<String> capturedCommand;

    @BeforeEach
    void setup() throws Exception {
        config = mock(BackupConfig.class);
        shell = mock(ShellCommand.class);
        when(config.getProjectRoot()).thenReturn(tempDir);
        when(config.getNextcloudOcc()).thenReturn(Path.of("/snap/bin/nextcloud.occ"));
        when(shell.runCapture(anyString(), eq("config:system:get"), anyString()))
                .thenAnswer(invocation -> switch (invocation.getArgument(2, String.class)) {
                    case "dbhost" -> "/var/snap/nextcloud/mysql.sock";
                    case "dbname" -> "nextcloud";
                    case "dbuser" -> "ncuser";
                    case "dbpassword" -> "secret";
                    default -> "";
                });
        doAnswer(invocation -> {
            capturedCommand = Arrays.stream(invocation.getArguments()).skip(1)
                    .map(String.class::cast).toList();
            invocation.getArgument(0, OutputStream.class).write("sql dump".getBytes());
            return null;
        }).when(shell).runTo(any(OutputStream.class), any(String[].class));
        service = new DatabaseService(config, shell);
    }

    @Test
    void dumpUsesSocketWhenHostIsPath() throws Exception {
        service.dump();
        assertTrue(capturedCommand.containsAll(Arrays.asList("--socket", "/var/snap/nextcloud/mysql.sock")));
    }

    @Test
    void dumpUsesTcpWhenHostIsHostname() throws Exception {
        when(shell.runCapture(anyString(), eq("config:system:get"), eq("dbhost"))).thenReturn("db.internal");
        service.dump();
        assertTrue(capturedCommand.containsAll(Arrays.asList("-h", "db.internal")));
    }

    @Test
    void dumpStoresOutputPath() throws Exception {
        service.dump();
        assertTrue(Files.isRegularFile(service.getLastDumpPath()));
        assertTrue(service.getLastDumpPath().toString().endsWith("-nextcloud.sql.gz"));
    }

    @Test
    void getLastDumpPathThrowsBeforeDump() {
        assertThrows(IllegalStateException.class, service::getLastDumpPath);
    }

}
