package dev.irattiz.backup.service;

import dev.irattiz.backup.logging.BackupLogger;
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.slf4j.Logger;

import java.io.StringReader;
import java.util.Locale;

/** Converts Restic JSON Lines output into compact operational log messages. */
public final class ResticOutputFormatter {

    private static final Logger log = BackupLogger.getLogger(ResticOutputFormatter.class);

    private String lastStatus;
    private String snapshotId;

    public void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        try (JsonReader reader = Json.createReader(new StringReader(line))) {
            JsonObject message = reader.readObject();
            switch (message.getString("message_type", "")) {
                case "status" -> logStatus(message);
                case "summary" -> logSummary(message);
                case "error" -> logError(message);
                case "verbose_status", "excluded_item" -> log.debug("Restic: {}", line);
                default -> log.debug("Unknown Restic JSON message: {}", line);
            }
        } catch (Exception e) {
            if (line.regionMatches(true, 0, "error", 0, 5)
                    || line.regionMatches(true, 0, "fatal", 0, 5)) {
                log.warn("Restic: {}", line);
            } else {
                log.info("Restic: {}", line);
            }
        }
    }

    public ResticBackupResult result() {
        return new ResticBackupResult(snapshotId);
    }

    private void logStatus(JsonObject message) {
        long filesDone = longValue(message, "files_done");
        long totalFiles = longValue(message, "total_files");
        long bytesDone = longValue(message, "bytes_done");
        long totalBytes = longValue(message, "total_bytes");
        long elapsed = longValue(message, "seconds_elapsed");
        long remaining = longValue(message, "seconds_remaining");
        long errors = longValue(message, "error_count");

        String formatted;
        if (message.containsKey("percent_done")) {
            double percent = doubleValue(message, "percent_done") * 100.0;
            formatted = String.format(
                    Locale.ROOT,
                    "Restic progress: %.1f%% — %,d/%,d files — %s/%s — elapsed %s%s%s",
                    percent,
                    filesDone,
                    totalFiles,
                    formatBytes(bytesDone),
                    formatBytes(totalBytes),
                    formatDuration(elapsed),
                    remaining > 0 ? " — ETA " + formatDuration(remaining) : "",
                    errors > 0 ? " — errors " + errors : "");
        } else {
            formatted = String.format(
                    Locale.ROOT,
                    "Restic scanning: %,d files discovered — %s — elapsed %s%s",
                    totalFiles,
                    formatBytes(totalBytes),
                    formatDuration(elapsed),
                    errors > 0 ? " — errors " + errors : "");
        }

        if (!formatted.equals(lastStatus)) {
            log.info(formatted);
            lastStatus = formatted;
        }
    }

    private void logSummary(JsonObject message) {
        snapshotId = stringValue(message, "snapshot_id");
        long files = longValue(message, "total_files_processed");
        long bytes = longValue(message, "total_bytes_processed");
        long added = longValue(message, "data_added_packed");
        if (added == 0) {
            added = longValue(message, "data_added");
        }
        long duration = Math.round(doubleValue(message, "total_duration"));

        log.info("Restic summary: {} files — {} processed — {} stored — duration {}{}",
                String.format(Locale.ROOT, "%,d", files),
                formatBytes(bytes),
                formatBytes(added),
                formatDuration(duration),
                snapshotId != null ? " — snapshot " + snapshotId : "");
    }

    private void logError(JsonObject message) {
        String error = errorMessage(message.get("error"));
        String during = stringValue(message, "during");
        String item = stringValue(message, "item");

        StringBuilder formatted = new StringBuilder("Restic error");
        if (during != null) {
            formatted.append(" during ").append(during);
        }
        if (item != null) {
            formatted.append(" for ").append(item);
        }
        if (error != null) {
            formatted.append(": ").append(error);
        }
        log.error(formatted.toString());
    }

    private static String errorMessage(JsonValue value) {
        if (value instanceof JsonString string) {
            return string.getString();
        }
        if (value instanceof JsonObject object) {
            return stringValue(object, "message");
        }
        return value != null && value != JsonValue.NULL ? value.toString() : null;
    }

    private static long longValue(JsonObject object, String name) {
        JsonNumber value = object.getJsonNumber(name);
        return value != null ? value.longValue() : 0;
    }

    private static double doubleValue(JsonObject object, String name) {
        JsonNumber value = object.getJsonNumber(name);
        return value != null ? value.doubleValue() : 0;
    }

    private static String stringValue(JsonObject object, String name) {
        JsonValue value = object.get(name);
        return value instanceof JsonString string ? string.getString() : null;
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) {
            return "%dh %dm".formatted(hours, minutes);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, remainder);
        }
        return remainder + "s";
    }
}
