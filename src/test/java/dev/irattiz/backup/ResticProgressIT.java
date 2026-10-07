package dev.irattiz.backup;

import dev.irattiz.backup.service.ResticBackupResult;
import dev.irattiz.backup.service.ResticOutputFormatter;
import dev.irattiz.backup.util.ShellCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class ResticProgressIT {

    @TempDir
    Path tempDir;

    @Test
    void streamsFakeResticJsonIntoFormatter() throws Exception {
        Path fakeRestic = tempDir.resolve("fake-restic.sh");
        Files.writeString(fakeRestic, """
                printf '%s\\n' \
                  '{"message_type":"status","percent_done":0.5,"files_done":5,"total_files":10,"bytes_done":512,"total_bytes":1024,"seconds_elapsed":2,"seconds_remaining":2,"error_count":0}' \
                  '{"message_type":"summary","total_files_processed":10,"total_bytes_processed":1024,"data_added_packed":512,"total_duration":4,"snapshot_id":"feed1234"}'
                """);

        ResticOutputFormatter formatter = new ResticOutputFormatter();
        new ShellCommand(false).runStreaming(
                Map.of("RESTIC_PROGRESS_FPS", "0.033333"),
                formatter::accept,
                "sh", fakeRestic.toString());

        ResticBackupResult result = formatter.result();
        assertEquals("feed1234", result.snapshotId());
    }
}
