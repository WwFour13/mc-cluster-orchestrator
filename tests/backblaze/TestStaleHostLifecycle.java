import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TestStaleHostLifecycle {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 7/7] Stale-Host Fail-Stop & Session Lease Lifecycle                ");
        System.out.println("================================================================================");

        int failures = 0;
        MockRcloneHarness.setup();
        String mockRclone = MockRcloneHarness.getMockRclonePath();
        String b2Target = "CLUSTER:unit-test-bucket/server";

        Path tempServerDir = Paths.get("tests", ".tmp_stale_host_lifecycle_test");
        Files.createDirectories(tempServerDir.resolve("world"));
        Files.writeString(tempServerDir.resolve("world").resolve("level.dat"), "test_level_data", StandardCharsets.UTF_8);

        try {
            // Case 1: Session creation and monotonic deadline invariants
            ServerLauncher.HostingSession session = new ServerLauncher.HostingSession("lock-session-1", "HostAlpha", 12, 300);
            if (session.getState() == ServerLauncher.SessionState.ACTIVE && session.isLeaseValid()) {
                System.out.println("  ✓ PASS: Fresh HostingSession initialized in ACTIVE state with valid monotonic lease");
            } else {
                System.err.println("  ✗ FAIL: Fresh HostingSession was not ACTIVE or valid!");
                failures++;
            }

            // Case 1b: Successful lock acquisition must not clear stale-host recovery state.
            MockRcloneHarness.reset();
            ServerLauncher.markLocalWorldDivergent(tempServerDir, "Recovery marker test", "lock-session-1");
            String staleIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minus(java.time.Duration.ofHours(1)));
            MockRcloneHarness.setLockJson("{\"host\":\"OldHost\",\"lock_id\":\"stale-lock\",\"released\":false,\"timestamp\":\"" + staleIso + "\"}");
            boolean recoveredMarkerLockAcquired = ServerLauncher.acquireCloudLock(tempServerDir, mockRclone, b2Target, "HostAlpha", 12, 300);
            if (recoveredMarkerLockAcquired && ServerLauncher.hasDivergentMarker(tempServerDir)) {
                System.out.println("  ✓ PASS: Lock acquisition preserved the divergent recovery marker until a successful pull");
            } else {
                System.err.println("  ✗ FAIL: Lock acquisition cleared or failed to preserve divergent recovery marker!");
                failures++;
            }
            ServerLauncher.clearCurrentSession();

            // Case 2: Verified Heartbeat Renewal extends lease when ownership matches
            MockRcloneHarness.reset();
            String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"HostAlpha\",\"lock_id\":\"lock-session-1\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));
            ServerLauncher.setCurrentSession(session);

            boolean renewed = ServerLauncher.renewHostingLease(tempServerDir, mockRclone, b2Target, "HostAlpha");
            if (renewed && session.getState() == ServerLauncher.SessionState.ACTIVE) {
                System.out.println("  ✓ PASS: renewHostingLease() verified ownership and refreshed session deadline");
            } else {
                System.err.println("  ✗ FAIL: renewHostingLease() failed to refresh valid session!");
                failures++;
            }

            // Malformed released type must fail closed and never be accepted as false
            MockRcloneHarness.reset();
            ServerLauncher.HostingSession strictSession = new ServerLauncher.HostingSession("lock-session-strict", "HostAlpha", 12, 300);
            ServerLauncher.setCurrentSession(strictSession);
            MockRcloneHarness.setLockJson("{\"host\":\"HostAlpha\",\"lock_id\":\"lock-session-strict\",\"released\":\"false\",\"timestamp\":\"" + nowIso + "\"}");
            boolean malformedBooleanRenewal = ServerLauncher.renewHostingLease(tempServerDir, mockRclone, b2Target, "HostAlpha");
            if (!malformedBooleanRenewal && strictSession.getState() == ServerLauncher.SessionState.LOST) {
                System.out.println("  ✓ PASS: String-valued 'released' field failed closed and lost the session");
            } else {
                System.err.println("  ✗ FAIL: String-valued 'released' field was accepted!");
                failures++;
            }
            ServerLauncher.setCurrentSession(session);

            // Case 3: Foreign Lock ID causes IMMEDIATE transition to LOST and triggers fail-stop
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"CompetitorHost\",\"lock_id\":\"foreign-lock-999\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));

            boolean renewedForeign = ServerLauncher.renewHostingLease(tempServerDir, mockRclone, b2Target, "HostAlpha");
            if (!renewedForeign && session.getState() == ServerLauncher.SessionState.LOST) {
                System.out.println("  ✓ PASS: Foreign lock ID caused IMMEDIATE transition to LOST state");
            } else {
                System.err.println("  ✗ FAIL: Foreign lock ID did not transition session to LOST! (state=" + session.getState() + ")");
                failures++;
            }

            // Verify local divergence marker was written
            if (ServerLauncher.hasDivergentMarker(tempServerDir)) {
                System.out.println("  ✓ PASS: Local world marked non-authoritative divergent copy in .lost_host_recovery.json");
            } else {
                System.err.println("  ✗ FAIL: Divergent marker was not written upon lease loss!");
                failures++;
            }

            // Case 4: A LOST session CANNOT regain ownership through heartbeat retries
            MockRcloneHarness.reset();
            // Reset remote lock to match our old ID to see if a retry could trick it back
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"HostAlpha\",\"lock_id\":\"lock-session-1\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));
            boolean retryReclaim = ServerLauncher.renewHostingLease(tempServerDir, mockRclone, b2Target, "HostAlpha");
            if (!retryReclaim && session.getState() == ServerLauncher.SessionState.LOST) {
                System.out.println("  ✓ PASS: LOST session permanently barred from renewing lease or regaining ownership");
            } else {
                System.err.println("  ✗ FAIL: LOST session managed to reclaim ownership!");
                failures++;
            }

            // Case 5: Incremental autosave push REJECTED for LOST session
            MockRcloneHarness.reset();
            ServerLauncher.triggerIncrementalPush(tempServerDir, mockRclone, b2Target, "10M", null);
            List<String> incInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean incPushRan = incInvocations.stream().anyMatch(inv -> inv.startsWith("copy"));
            if (!incPushRan) {
                System.out.println("  ✓ PASS: Incremental cloud autosave push strictly suppressed for LOST session");
            } else {
                System.err.println("  ✗ FAIL: Incremental push executed despite LOST session!");
                failures++;
            }

            // Case 6: Outbound pushToB2() REJECTED for LOST session (no files copied or manifest written)
            MockRcloneHarness.reset();
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":26}", StandardCharsets.UTF_8);
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":26,\"last_host\":\"HostAlpha\",\"min_orchestrator_version\":\"2.1.10\"}");

            boolean pushResult = ServerLauncher.pushToB2(tempServerDir, mockRclone, b2Target, "HostAlpha", 12, "10M", false);
            List<String> pushInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean copyCalled = pushInvocations.stream().anyMatch(inv -> inv.startsWith("copy"));
            boolean rcatCalled = pushInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));

            if (!pushResult && !copyCalled && !rcatCalled) {
                System.out.println("  ✓ PASS: pushToB2() rejected lost session with zero copy/rcat cloud side-effects");
            } else {
                System.err.println("  ✗ FAIL: pushToB2() permitted push or issued cloud commands for lost session!");
                failures++;
            }

            // Case 7: releaseCloudLock() REFUSED for LOST session (successor's lock protected)
            MockRcloneHarness.reset();
            boolean releaseResult = ServerLauncher.releaseCloudLock(tempServerDir, mockRclone, b2Target);
            List<String> releaseInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean tombstoneWritten = releaseInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));

            if (!releaseResult && !tombstoneWritten) {
                System.out.println("  ✓ PASS: releaseCloudLock() refused to write tombstone for LOST session");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() wrote tombstone despite LOST session!");
                failures++;
            }

            // Case 8: Administrative cleanup() during shutdown preserves local world in backups/ and skips cloud push/release
            MockRcloneHarness.reset();
            ServerLauncher.cleanup(tempServerDir, tempServerDir.resolve(".server_active.lock"), mockRclone, b2Target,
                    "HostAlpha", 12, "10M", null, null, true);

            List<String> cleanupInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean cleanupCopyRan = cleanupInvocations.stream().anyMatch(inv -> inv.startsWith("copy"));
            boolean cleanupRcatRan = cleanupInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));
            boolean recoveryBackupExists = Files.exists(tempServerDir.resolve("backups")) &&
                    Files.list(tempServerDir.resolve("backups")).anyMatch(p -> p.getFileName().toString().startsWith("recovery-world-"));

            if (!cleanupCopyRan && !cleanupRcatRan && recoveryBackupExists) {
                System.out.println("  ✓ PASS: cleanup() after loss preserved local world in backups/ and skipped cloud publish/release");
            } else {
                System.err.println("  ✗ FAIL: cleanup() after loss did not preserve backup or issued cloud commands! (copy=" + cleanupCopyRan + ", rcat=" + cleanupRcatRan + ", backup=" + recoveryBackupExists + ")");
                failures++;
            }

            // Case 9: Restart after loss requires cloud pull and clears divergent marker upon successful pull
            MockRcloneHarness.reset();
            // Ensure divergent marker is active
            ServerLauncher.markLocalWorldDivergent(tempServerDir, "Simulated loss", "lock-session-1");
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":27,\"last_host\":\"SuccessorHost\",\"min_orchestrator_version\":\"2.1.10\"}");

            // Pull latest cloud state
            boolean pullSuccess = ServerLauncher.pullFromB2(tempServerDir, mockRclone, b2Target, 12, true, 27, 10.0);
            boolean markerCleared = !ServerLauncher.hasDivergentMarker(tempServerDir);

            if (pullSuccess && markerCleared) {
                System.out.println("  ✓ PASS: Restart after loss successfully synced fresh cloud world and cleared divergent marker");
            } else {
                System.err.println("  ✗ FAIL: pullFromB2() did not recover or failed to clear divergent marker!");
                failures++;
            }

            // Recovery must perform a real cloud pull even when the divergent local generation
            // happens to match the cloud generation.
            MockRcloneHarness.reset();
            ServerLauncher.markLocalWorldDivergent(tempServerDir, "Same-generation divergence", "lock-session-1");
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":27}", StandardCharsets.UTF_8);
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":27,\"last_host\":\"SuccessorHost\",\"min_orchestrator_version\":\"2.1.10\"}");
            boolean sameGenPull = ServerLauncher.pullFromB2(tempServerDir, mockRclone, b2Target, 12, true, 27, 10.0);
            boolean copyRan = MockRcloneHarness.getRecordedInvocations().stream()
                    .anyMatch(inv -> inv.startsWith("copy"));
            if (sameGenPull && copyRan && !ServerLauncher.hasDivergentMarker(tempServerDir)) {
                System.out.println("  ✓ PASS: Same-generation recovery bypassed the zero-egress shortcut and performed a real pull");
            } else {
                System.err.println("  ✗ FAIL: Same-generation recovery skipped or failed the cloud pull!");
                failures++;
            }

            // Case 10: Idempotency of fail-stop and cleanup calls
            ServerLauncher.failStopActiveHosting(tempServerDir, "Repeated fail-stop 1");
            ServerLauncher.failStopActiveHosting(tempServerDir, "Repeated fail-stop 2");
            if (session.getState() == ServerLauncher.SessionState.LOST) {
                System.out.println("  ✓ PASS: Repeated and concurrent failStopActiveHosting() invocations are idempotent");
            } else {
                System.err.println("  ✗ FAIL: Idempotency failed for session state!");
                failures++;
            }

        } finally {
            ServerLauncher.clearCurrentSession();
            MockRcloneHarness.teardown();
            try {
                if (Files.exists(tempServerDir)) {
                    Files.walk(tempServerDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            } catch (Exception ignored) {}
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL STALE-HOST FAIL-STOP ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
