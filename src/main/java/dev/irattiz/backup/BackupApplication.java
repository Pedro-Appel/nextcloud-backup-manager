package dev.irattiz.backup;

import dev.irattiz.backup.context.BackupContext;
import dev.irattiz.backup.exception.BackupException;
import dev.irattiz.backup.logging.BackupLogger;
import org.slf4j.Logger;

import java.net.URISyntaxException;
import java.nio.file.Path;

public class BackupApplication {

    private static final Logger log = BackupLogger.getLogger(BackupApplication.class);

    public static void main(String[] args) throws URISyntaxException {
        Path projectRoot = resolveProjectRoot();
        try {
            BackupContext context = new BackupContext(projectRoot);
            log.info("Backup context initialised");
            // Full workflow wired in Task 13
        } catch (BackupException e) {
            System.err.println("FATAL: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Derives the project root from the location of this JAR.
     * When running from build/libs/app-all.jar the root is two levels up.
     * When running from IDE class files (build/classes/.../dev/irattiz/backup/)
     * we walk up to find the directory containing config/.
     */
    static Path resolveProjectRoot() throws URISyntaxException {
        Path codeLocation = Path.of(
                BackupApplication.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());

        // JAR case: build/libs/app-all.jar → parent = build/libs → parent = build → parent = root
        if (codeLocation.toString().endsWith(".jar")) {
            return codeLocation.getParent().getParent().getParent();
        }

        // IDE / test case: walk up until we find a directory containing config/
        Path candidate = codeLocation.toAbsolutePath().normalize();
        while (candidate != null) {
            if (candidate.resolve("config").toFile().isDirectory()) {
                return candidate;
            }
            candidate = candidate.getParent();
        }

        // Fallback to working directory
        return Path.of(System.getProperty("user.dir"));
    }
}
