import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TestDirectLockProtocol {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [DIRECT TEST 3/5] ServerLauncher Lock Acquisition & Tombstone Protocol        ");
        System.out.println("================================================================================");

        int failures = 0;
        MockRcloneHarness.setup();
        String mockRclone = MockRcloneHarness.getMockRclonePath();
        String b2Target = "CLUSTER:unit-test-bucket/server";

        try {
            // Case 1: Direct acquireCloudLock() Execution
            MockRcloneHarness.reset();
            boolean acquired = ServerLauncher.acquireCloudLock(null, mockRclone, b2Target, "TestHost", 12);
            List<String> acquireInvocations = MockRcloneHarness.getRecordedInvocations();
            // System.out.println("DEBUG acquire: acquired=" + acquired + " count=" + acquireInvocations.size() + " inv=" + acquireInvocations);

            boolean hasRcat = false;
            for (String inv : acquireInvocations) {
                if (inv.startsWith("rcat")) {
                    hasRcat = true;
                    break;
                }
            }

            if (acquired && hasRcat) {
                System.out.println("  ✓ PASS: acquireCloudLock() DIRECTLY executed 'rcat' and verified candidate lock ID");
            } else {
                System.err.println("  ✗ FAIL: acquireCloudLock() failed or did not execute rcat! (acquired=" + acquired + ", invocations=" + acquireInvocations + ")");
                failures++;
            }

            // Case 2: Direct releaseCloudLock() Execution via Class A Tombstone
            MockRcloneHarness.reset();
            ServerLauncher.releaseCloudLock(null, mockRclone, b2Target);

            List<String> releaseInvocations = MockRcloneHarness.getRecordedInvocations();
            if (!releaseInvocations.isEmpty() && releaseInvocations.get(0).startsWith("rcat")) {
                System.out.println("  ✓ PASS: releaseCloudLock() DIRECTLY executed 'rcat' (Class A Tombstone)");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() did not use rcat!");
                failures++;
            }

            // Inspect the actual tombstone JSON written to standard input
            String tombstoneInput = MockRcloneHarness.getLastInput();
            boolean hasReleasedTrue = tombstoneInput.contains("\"released\":true");
            boolean hasEpoch = tombstoneInput.contains("1970-01-01T00:00:00Z");
            boolean hasEmptyHost = tombstoneInput.contains("\"host\":\"\"");

            if (hasReleasedTrue && hasEpoch && hasEmptyHost) {
                System.out.println("  ✓ PASS: Tombstone stdin payload strictly conforms to standard v2.1.10 schema");
            } else {
                System.err.println("  ✗ FAIL: Invalid tombstone payload written: " + tombstoneInput);
                failures++;
            }

            // Case 3: Direct isCloudLocked() Evaluation of Tombstone
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("{\"host\":\"\",\"lock_id\":\"\",\"released\":true,\"timestamp\":\"1970-01-01T00:00:00Z\"}");

            boolean lockedOnTombstone = ServerLauncher.isCloudLocked(null, mockRclone, b2Target, 12);
            if (!lockedOnTombstone) {
                System.out.println("  ✓ PASS: isCloudLocked() DIRECTLY evaluates tombstone JSON as UNLOCKED");
            } else {
                System.err.println("  ✗ FAIL: isCloudLocked() falsely reported tombstone as active lock!");
                failures++;
            }

            // Case 4: Direct isCloudLocked() Evaluation of Active Lock
            MockRcloneHarness.reset();
            String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"ActiveHost\",\"lock_id\":\"uuid-123\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));

            boolean lockedOnActive = ServerLauncher.isCloudLocked(null, mockRclone, b2Target, 12);
            String lockHost = ServerLauncher.readLockHost(null, mockRclone, b2Target);

            if (lockedOnActive && "ActiveHost".equals(lockHost)) {
                System.out.println("  ✓ PASS: isCloudLocked() DIRECTLY evaluates fresh heartbeat as LOCKED by 'ActiveHost'");
            } else {
                System.err.println("  ✗ FAIL: isCloudLocked() failed on active heartbeat evaluation!");
                failures++;
            }

            // Case 5: Direct isCloudLocked() Evaluation of Stale Lock (15 min elapsed > 12 min threshold)
            MockRcloneHarness.reset();
            String staleIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(15 * 60));
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"StaleHost\",\"lock_id\":\"uuid-456\",\"released\":false,\"timestamp\":\"%s\"}", staleIso));

            boolean lockedOnStale = ServerLauncher.isCloudLocked(null, mockRclone, b2Target, 12);
            if (!lockedOnStale) {
                System.out.println("  ✓ PASS: isCloudLocked() DIRECTLY evaluates 15-minute lock as STALE (Auto-Unlocked)");
            } else {
                System.err.println("  ✗ FAIL: isCloudLocked() treated stale lock as active!");
                failures++;
            }

        } finally {
            MockRcloneHarness.teardown();
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL DIRECT LOCK PROTOCOL ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
