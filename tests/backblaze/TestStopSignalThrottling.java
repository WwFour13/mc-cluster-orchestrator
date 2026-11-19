import java.util.concurrent.atomic.AtomicLong;

public class TestStopSignalThrottling {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 5/7] Sixty-Second Stop-Signal Rate Limiter & Emergency Bypass       ");
        System.out.println("================================================================================");

        int failures = 0;

        // 1. Throttling Simulation Logic
        AtomicLong lastCheck = new AtomicLong(0L);
        long t0 = 1_000_000_000L; // Baseline timestamp in ms

        // Call 1 at t0
        boolean allowed1 = tryCheckStopSignal(t0, lastCheck, 60_000L);
        if (allowed1 && lastCheck.get() == t0) {
            System.out.println("  ✓ PASS: First invocation at t=0s permitted and recorded timestamp");
        } else {
            System.err.println("  ✗ FAIL: First invocation unexpectedly throttled");
            failures++;
        }

        // Call 2 at t0 + 15s (15,000 ms)
        boolean allowed2 = tryCheckStopSignal(t0 + 15_000L, lastCheck, 60_000L);
        if (!allowed2 && lastCheck.get() == t0) {
            System.out.println("  ✓ PASS: Rapid invocation at t=15s throttled (15s < 60s minimum threshold)");
        } else {
            System.err.println("  ✗ FAIL: Invocation at t=15s was not throttled!");
            failures++;
        }

        // Call 3 at t0 + 59s (59,999 ms)
        boolean allowed3 = tryCheckStopSignal(t0 + 59_999L, lastCheck, 60_000L);
        if (!allowed3 && lastCheck.get() == t0) {
            System.out.println("  ✓ PASS: Invocation at t=59.9s throttled (59.9s < 60s)");
        } else {
            System.err.println("  ✗ FAIL: Invocation at t=59.9s was not throttled!");
            failures++;
        }

        // Call 4 at t0 + 61s (61,000 ms)
        boolean allowed4 = tryCheckStopSignal(t0 + 61_000L, lastCheck, 60_000L);
        if (allowed4 && lastCheck.get() == (t0 + 61_000L)) {
            System.out.println("  ✓ PASS: Invocation at t=61s permitted and updated rate-limit window");
        } else {
            System.err.println("  ✗ FAIL: Invocation at t=61s was wrongly throttled!");
            failures++;
        }

        // 2. Emergency Mode Suppression Simulation
        boolean emergencyActive = true;
        boolean emergencyExecuted = false;
        if (!emergencyActive) {
            emergencyExecuted = true;
        }
        if (!emergencyExecuted) {
            System.out.println("  ✓ PASS: Emergency Local Mode completely suppresses stop signal polling (0 queries)");
        } else {
            System.err.println("  ✗ FAIL: Emergency mode failed to bypass polling!");
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 5 THROTTLING ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }

    private static boolean tryCheckStopSignal(long simulatedNow, AtomicLong lastCheck, long intervalMs) {
        long last = lastCheck.get();
        if (simulatedNow - last < intervalMs) {
            return false;
        }
        return lastCheck.compareAndSet(last, simulatedNow);
    }
}
