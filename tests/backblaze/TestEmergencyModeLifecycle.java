import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;

public class TestEmergencyModeLifecycle {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 6/7] Emergency Local Mode State, Persistence & Expiry              ");
        System.out.println("================================================================================");

        int failures = 0;
        Path tempDir = Paths.get("tests", ".tmp_emergency_lifecycle_test");
        Files.createDirectories(tempDir);

        try {
            // Assertion 1: Fresh directory starts inactive
            ServerLauncher.clearEmergencyMode(tempDir);
            if (!ServerLauncher.isEmergencyModeActive(tempDir)) {
                System.out.println("  ✓ PASS: Fresh workspace initial state is NOT in Emergency Mode");
            } else {
                System.err.println("  ✗ FAIL: Fresh workspace falsely reported Emergency Mode as active");
                failures++;
            }

            // Assertion 2: Activate Emergency Mode and verify disk state
            ServerLauncher.activateEmergencyMode(tempDir, "TesterHost", 26, "QUOTA_EXCEEDED");
            Path flagFile = tempDir.resolve(".emergency_host.json");

            if (Files.exists(flagFile)) {
                System.out.println("  ✓ PASS: .emergency_host.json created on disk");
            } else {
                System.err.println("  ✗ FAIL: .emergency_host.json was not created on disk!");
                failures++;
            }

            String content = Files.readString(flagFile);
            String host = ServerLauncher.extractJsonString(content, "host");
            int gen = ServerLauncher.extractJsonInt(content, "local_generation", -1);
            String expStr = ServerLauncher.extractJsonString(content, "expires_at");

            if ("TesterHost".equals(host) && gen == 26 && expStr != null && !expStr.isEmpty()) {
                System.out.println("  ✓ PASS: Flag payload records host ('TesterHost'), generation (#26), and expiry");
            } else {
                System.err.println("  ✗ FAIL: Flag payload contains invalid properties: " + content);
                failures++;
            }

            Instant exp = Instant.parse(expStr);
            if (exp.isAfter(Instant.now())) {
                System.out.printf("  ✓ PASS: Expiry timestamp correctly set in the future (%s)%n", expStr);
            } else {
                System.err.println("  ✗ FAIL: Expiry timestamp is in the past: " + expStr);
                failures++;
            }

            // Assertion 3: Active state verified via isEmergencyModeActive()
            if (ServerLauncher.isEmergencyModeActive(tempDir)) {
                System.out.println("  ✓ PASS: isEmergencyModeActive() evaluates to TRUE when valid flag is present");
            } else {
                System.err.println("  ✗ FAIL: isEmergencyModeActive() returned false with valid flag");
                failures++;
            }

            // Assertion 4: Automatic purge and deactivation when flag is expired
            ServerLauncher.emergencyModeActive.set(false); // Reset in-memory cache to force disk evaluation
            String expiredJson = "{\"host\":\"OldHost\",\"local_generation\":25,\"expires_at\":\"2020-01-01T00:00:00Z\"}";
            Files.writeString(flagFile, expiredJson);

            boolean expiredActive = ServerLauncher.isEmergencyModeActive(tempDir);
            if (!expiredActive) {
                System.out.println("  ✓ PASS: Expired emergency flag evaluated as INACTIVE");
            } else {
                System.err.println("  ✗ FAIL: Expired flag was falsely evaluated as active");
                failures++;
            }

            if (!Files.exists(flagFile)) {
                System.out.println("  ✓ PASS: Expired .emergency_host.json automatically deleted from disk");
            } else {
                System.err.println("  ✗ FAIL: Expired flag was not purged from disk!");
                failures++;
            }

            // Assertion 5: Manual clearing
            ServerLauncher.activateEmergencyMode(tempDir, "TesterHost", 26, "QUOTA_EXCEEDED");
            ServerLauncher.clearEmergencyMode(tempDir);
            if (!Files.exists(flagFile) && !ServerLauncher.isEmergencyModeActive(tempDir)) {
                System.out.println("  ✓ PASS: clearEmergencyMode() cleanly deletes file and resets atomic state");
            } else {
                System.err.println("  ✗ FAIL: clearEmergencyMode() did not clean up properly");
                failures++;
            }

        } finally {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 7 EMERGENCY MODE ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
