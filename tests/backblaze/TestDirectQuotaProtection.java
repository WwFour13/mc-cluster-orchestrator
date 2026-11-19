import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;

public class TestDirectQuotaProtection {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [DIRECT TEST 2/5] ServerLauncher Stop-Signal & Emergency Quota Protection     ");
        System.out.println("================================================================================");

        int failures = 0;
        MockRcloneHarness.setup();
        String mockRclone = MockRcloneHarness.getMockRclonePath();
        String b2Target = "CLUSTER:unit-test-bucket/server";

        Path tempDir = Paths.get("tests", ".tmp_quota_test");
        Files.createDirectories(tempDir);

        try {
            // Assertion 1: Rapid checkRemoteStopSignal() rate-limiting
            MockRcloneHarness.reset();
            ServerLauncher.lastStopSignalCheckMs.set(0L); // Reset atomic clock
            ServerLauncher.remoteStopTriggered.set(false);
            ServerLauncher.clearEmergencyMode(tempDir);

            // Call 1
            ServerLauncher.checkRemoteStopSignal(tempDir, mockRclone, b2Target, null);
            // Call 2 immediately (0ms elapsed)
            ServerLauncher.checkRemoteStopSignal(tempDir, mockRclone, b2Target, null);
            // Call 3 immediately
            ServerLauncher.checkRemoteStopSignal(tempDir, mockRclone, b2Target, null);

            List<String> calls = MockRcloneHarness.getRecordedInvocations();
            if (calls.size() == 1) {
                System.out.println("  ✓ PASS: Direct checkRemoteStopSignal() executed exactly 1 command; 2 rapid calls throttled");
            } else {
                System.err.printf("  ✗ FAIL: Expected exactly 1 rclone call, recorded: %d%n", calls.size());
                failures++;
            }

            // Assertion 2: Emergency Mode Zero-Quota Bypass
            MockRcloneHarness.reset();
            ServerLauncher.lastStopSignalCheckMs.set(0L); // Reset timer
            ServerLauncher.activateEmergencyMode(tempDir, "TestHost", 26, "QUOTA_EXCEEDED");

            // Execute check under active Emergency Mode
            ServerLauncher.checkRemoteStopSignal(tempDir, mockRclone, b2Target, null);
            List<String> emergencyCalls = MockRcloneHarness.getRecordedInvocations();

            if (emergencyCalls.isEmpty()) {
                System.out.println("  ✓ PASS: Emergency Mode completely suppressed checkRemoteStopSignal() (0 API queries)");
            } else {
                System.err.printf("  ✗ FAIL: Emergency Mode failed to suppress queries (Recorded: %d)%n", emergencyCalls.size());
                failures++;
            }

            // Assertion 3: Stop Signal Cancellation Command
            ServerLauncher.clearEmergencyMode(tempDir);
            MockRcloneHarness.reset();
            ServerLauncher.cancelRemoteStop(tempDir, mockRclone, b2Target);

            List<String> cancelCalls = MockRcloneHarness.getRecordedInvocations();
            String cancelCall = cancelCalls.isEmpty() ? "" : cancelCalls.get(0);

            if (cancelCall.startsWith("delete") && cancelCall.contains("--include") && cancelCall.contains("/remote_stop.signal")) {
                System.out.println("  ✓ PASS: cancelRemoteStop() passed exact Class A delete command to ProcessBuilder");
            } else {
                System.err.println("  ✗ FAIL: cancelRemoteStop() generated invalid command: " + cancelCall);
                failures++;
            }

        } finally {
            MockRcloneHarness.teardown();
            ServerLauncher.clearEmergencyMode(tempDir);
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL DIRECT QUOTA PROTECTION ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
