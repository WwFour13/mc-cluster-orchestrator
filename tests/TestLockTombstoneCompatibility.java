import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class TestLockTombstoneCompatibility {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 4/7] Lock Tombstone Dual-Architecture Compatibility                ");
        System.out.println("================================================================================");

        int failures = 0;

        // The Standard v2.1.10 Tombstone Payload
        String tombstoneJson = "{\"host\":\"\",\"lock_id\":\"\",\"released\":true,\"timestamp\":\"1970-01-01T00:00:00Z\"}";

        // 1. Modern Node Evaluation (v2.1.10+)
        boolean modernReleased = ServerLauncher.extractJsonBoolean(tombstoneJson, "released", false);
        String modernHost = ServerLauncher.extractJsonString(tombstoneJson, "host");

        boolean modernIsLocked = (!modernReleased && modernHost != null && !modernHost.trim().isEmpty());
        if (!modernIsLocked) {
            System.out.println("  ✓ PASS: Modern node evaluates tombstone as UNLOCKED via 'released: true'");
        } else {
            System.err.println("  ✗ FAIL: Modern node falsely evaluated tombstone as locked!");
            failures++;
        }

        // 2. Legacy Node Evaluation (v2.1.9 and earlier)
        // Legacy nodes do not know about 'released'. They inspect 'timestamp' and compare with stale timeout.
        String legacyHost = ServerLauncher.extractJsonString(tombstoneJson, "host");
        String legacyTimestamp = ServerLauncher.extractJsonString(tombstoneJson, "timestamp");

        Instant beatTime = Instant.parse(legacyTimestamp);
        long elapsedMinutes = Duration.between(beatTime, Instant.now()).toMinutes();
        int staleThreshold = 12; // 12 minutes default

        if (elapsedMinutes > 29_000_000L) {
            System.out.printf("  ✓ PASS: Epoch timestamp evaluates to %d elapsed minutes (>29M min)%n", elapsedMinutes);
        } else {
            System.err.println("  ✗ FAIL: Unexpected elapsed time calculation: " + elapsedMinutes);
            failures++;
        }

        boolean legacyIsLocked = (elapsedMinutes < staleThreshold && legacyHost != null && !legacyHost.isEmpty());
        if (!legacyIsLocked && legacyHost != null && legacyHost.isEmpty()) {
            System.out.printf("  ✓ PASS: Legacy node evaluates tombstone as STALE/UNLOCKED (%d min >= %d min stale, host is empty)%n", elapsedMinutes, staleThreshold);
        } else {
            System.err.println("  ✗ FAIL: Legacy node falsely evaluated tombstone as active lock!");
            failures++;
        }

        // 3. Active Heartbeat Verification
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String activeHeartbeatJson = String.format("{\"host\":\"ClusterNode-Alpha\",\"lock_id\":\"uuid-test-999\",\"released\":false,\"timestamp\":\"%s\"}", nowIso);

        boolean activeReleased = ServerLauncher.extractJsonBoolean(activeHeartbeatJson, "released", false);
        String activeHost = ServerLauncher.extractJsonString(activeHeartbeatJson, "host");
        long activeElapsed = Duration.between(Instant.parse(nowIso), Instant.now()).toMinutes();

        boolean modernActiveLocked = (!activeReleased && activeHost != null && !activeHost.isEmpty());
        boolean legacyActiveLocked = (activeElapsed < staleThreshold && activeHost != null && !activeHost.isEmpty());

        if (modernActiveLocked && legacyActiveLocked) {
            System.out.println("  ✓ PASS: Both Modern and Legacy nodes unanimously agree active heartbeat is LOCKED");
        } else {
            System.err.println("  ✗ FAIL: Active heartbeat conflict between modern and legacy logic");
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 4 TOMBSTONE COMPATIBILITY ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
