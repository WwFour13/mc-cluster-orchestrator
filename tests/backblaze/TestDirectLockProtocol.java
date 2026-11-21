import java.io.IOException;
import java.nio.file.Files;
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
            boolean hasReleaseRcat = false;
            for (String inv : releaseInvocations) {
                if (inv.startsWith("rcat")) {
                    hasReleaseRcat = true;
                    break;
                }
            }
            if (hasReleaseRcat) {
                System.out.println("  ✓ PASS: releaseCloudLock() DIRECTLY executed 'rcat' (Class A Tombstone)");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() did not use rcat! (invocations=" + releaseInvocations + ")");
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

            // Case 6: Fenced Foreign Lock Protection
            // When another host ('ForeignHost') holds an active lock with 'foreign-lock-id'
            // and our session has no lock ownership, releaseCloudLock() MUST refuse to overwrite!
            MockRcloneHarness.reset();
            String foreignLockJson = String.format("{\"host\":\"ForeignHost\",\"lock_id\":\"foreign-lock-id\",\"released\":false,\"timestamp\":\"%s\"}", nowIso);
            MockRcloneHarness.setLockJson(foreignLockJson);

            boolean foreignReleased = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-candidate-id");
            if (!foreignReleased) {
                System.out.println("  ✓ PASS: releaseCloudLock() REFUSED to release foreign lock held by 'ForeignHost'");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() overwrote foreign active lock!");
                failures++;
            }

            // Case 7: Emergency forceReleaseCloudLock() overrides foreign lock
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson(foreignLockJson);
            boolean forceReleased = ServerLauncher.forceReleaseCloudLock(null, mockRclone, b2Target);
            if (forceReleased) {
                System.out.println("  ✓ PASS: forceReleaseCloudLock() successfully executed emergency override");
            } else {
                System.err.println("  ✗ FAIL: forceReleaseCloudLock() failed emergency override!");
                failures++;
            }

            // Case 8: Fail-closed lock release on remote read error (e.g. network/auth/timeout)
            MockRcloneHarness.reset();
            MockRcloneHarness.setRule("lockReadExitCode", "1");
            boolean releaseOnNetError = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-candidate-id");
            if (!releaseOnNetError) {
                System.out.println("  ✓ PASS: releaseCloudLock() FAILS CLOSED (aborts release) on cloud read errors");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() wrote tombstone despite cloud read error!");
                failures++;
            }

            // Case 9: Fail-closed lock release on empty lock payload
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("   ");
            boolean releaseOnEmpty = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-candidate-id");
            if (!releaseOnEmpty) {
                System.out.println("  ✓ PASS: releaseCloudLock() FAILS CLOSED on empty/blank lock payload");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() released lock despite empty payload!");
                failures++;
            }

            // Case 10: Fail-closed lock release on malformed/unparseable lock JSON
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("INVALID_CORRUPTED_NON_JSON_DATA");
            boolean releaseOnMalformed = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-candidate-id");
            if (!releaseOnMalformed) {
                System.out.println("  ✓ PASS: releaseCloudLock() FAILS CLOSED on malformed/unparseable lock payload");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() released lock despite malformed payload!");
                failures++;
            }

            // Case 11: Fail-closed lock release on plausible unowned active lock
            // JSON is syntactically valid with empty host/id and released:false, but caller holds no lock ID
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"\",\"lock_id\":\"\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));
            boolean releaseOnUnowned = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, null);
            if (!releaseOnUnowned) {
                System.out.println("  ✓ PASS: releaseCloudLock() FAILS CLOSED on unowned plausible active lock");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() released unowned active lock!");
                failures++;
            }

            // Case 12: Distributed Lease Expiration Guard
            // Holding our own matching lock ID, but lease expired (> 12 min elapsed)
            // releaseCloudLock() MUST fail closed and withhold tombstone write to prevent clobbering successor hosts
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"TestHost\",\"lock_id\":\"our-expired-lock-id\",\"released\":false,\"timestamp\":\"%s\"}", staleIso));
            boolean releaseOnExpiredLease = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-expired-lock-id");
            List<String> expiredInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean wroteTombstoneOnExpired = expiredInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));
            if (!releaseOnExpiredLease && !wroteTombstoneOnExpired) {
                System.out.println("  ✓ PASS: releaseCloudLock() WITHHELD tombstone on expired lease (>12 min) to protect successor hosts");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() wrote tombstone despite expired lease!");
                failures++;
            }

            // Case 13: Authorized release with matching lock ID and unexpired lease
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson(String.format("{\"host\":\"TestHost\",\"lock_id\":\"our-valid-lock-id\",\"released\":false,\"timestamp\":\"%s\"}", nowIso));
            boolean releaseValid = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "our-valid-lock-id");
            List<String> validInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean wroteTombstoneValid = validInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));
            if (releaseValid && wroteTombstoneValid) {
                System.out.println("  ✓ PASS: releaseCloudLock() successfully released authorized lock and wrote tombstone");
            } else {
                System.err.println("  ✗ FAIL: releaseCloudLock() failed authorized release!");
                failures++;
            }

            // Case 14: Interleaved Successor Lock — Real Race Interleaving via Mock Hook
            // Protocol: Host A reads lock-a (ownership passes), then BEFORE the tombstone reaches
            // the store, Host B acquires lock-b. The mock hook simulates this by swapping the
            // lock file when the next rcat to cluster.lock is called.
            // Expected: (a) rcat tombstone was attempted, (b) post-write read-back finds lock-b
            // (not released), (c) releaseCloudLock() returns false, (d) lock file still has lock-b.
            //
            // NOTE (PROVIDER LIMITATION — UNRESOLVED): Backblaze B2 does not support conditional
            // PutObject (If-Match). The tombstone is therefore written unconditionally. The mock
            // hook replaces it with Host B's lock before the write completes, allowing the
            // post-write read-back to detect the clobber and return false. In production on B2,
            // the tombstone WOULD overwrite Host B's lock with no 412 error. The test verifies
            // that the read-back detection path functions correctly, but it does not eliminate the
            // race — it documents it. A production fix requires a provider that supports atomic
            // conditional writes or a separate coordination service.
            MockRcloneHarness.reset();
            String hostALockJson = String.format("{\"host\":\"HostA\",\"lock_id\":\"lock-a\",\"released\":false,\"timestamp\":\"%s\"}", nowIso);
            String hostBLockJson = String.format("{\"host\":\"HostB\",\"lock_id\":\"lock-b\",\"released\":false,\"timestamp\":\"%s\"}", nowIso);
            // Start: Host A's lock is in the store (Host A is about to release it)
            MockRcloneHarness.setLockJson(hostALockJson);
            // Arm the hook: when Host A's rcat tombstone arrives, the mock will write Host B's lock
            // instead — simulating Host B winning the write between Host A's read and write.
            MockRcloneHarness.setLockReplaceOnNextWrite(hostBLockJson);

            // Host A attempts to release 'lock-a'. The tombstone write hits the mock hook,
            // so the store ends up with lock-b. Post-write read-back detects this.
            boolean interleavedReleased = ServerLauncher.releaseCloudLock(null, mockRclone, b2Target, "lock-a");
            List<String> interleavedInvocations = MockRcloneHarness.getRecordedInvocations();
            boolean tombstoneAttempted = interleavedInvocations.stream().anyMatch(inv -> inv.startsWith("rcat"));
            String remainingLockContent = Files.exists(MockRcloneHarness.LOCK_FILE)
                    ? Files.readString(MockRcloneHarness.LOCK_FILE).trim() : "";
            boolean successorLockIntact = remainingLockContent.contains("lock-b");

            if (!interleavedReleased && tombstoneAttempted && successorLockIntact) {
                System.out.println("  ✓ PASS: releaseCloudLock() attempted tombstone, detected interleaved successor (lock-b) via read-back, returned false; successor lock intact");
                System.out.println("    [DOCUMENTED RACE]: On B2 (no conditional PutObject), tombstone WOULD overwrite lock-b. Post-write read-back narrows but does not close the race.");
            } else {
                System.err.println("  ✗ FAIL: Case 14 interleaved successor test failed!");
                System.err.println("    interleavedReleased=" + interleavedReleased
                        + " tombstoneAttempted=" + tombstoneAttempted
                        + " successorLockIntact=" + successorLockIntact
                        + " remainingLock=" + remainingLockContent);
                failures++;
            }

            // Case 15: Fail-Closed isCloudLocked() on Malformed Lock Payload
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("CORRUPT_MALFORMED_NON_JSON_DATA");
            boolean lockedOnMalformed = ServerLauncher.isCloudLocked(null, mockRclone, b2Target, 12);
            if (lockedOnMalformed) {
                System.out.println("  ✓ PASS: isCloudLocked() FAILS CLOSED (evaluates as locked) on malformed payload");
            } else {
                System.err.println("  ✗ FAIL: isCloudLocked() failed open on malformed lock payload!");
                failures++;
            }

            // Case 16: Fail-Closed acquireCloudLock() on Malformed Lock Payload
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("CORRUPT_MALFORMED_NON_JSON_DATA");
            boolean acquiredOnMalformed = ServerLauncher.acquireCloudLock(null, mockRclone, b2Target, "NewHost", 12);
            if (!acquiredOnMalformed) {
                System.out.println("  ✓ PASS: acquireCloudLock() FAILS CLOSED (aborts acquisition) on malformed payload");
            } else {
                System.err.println("  ✗ FAIL: acquireCloudLock() acquired lock despite malformed remote state!");
                failures++;
            }

            // Case 17: Strict Manifest Parsing in checkCloudGeneration()
            MockRcloneHarness.reset();
            MockRcloneHarness.setManifestJson("CORRUPTED_NON_JSON_MANIFEST generation: 26");
            ServerLauncher.CloudGenerationResult corruptGenResult = ServerLauncher.checkCloudGeneration(null, mockRclone, b2Target);
            if (corruptGenResult.status() == ServerLauncher.CloudGenerationResult.Status.CORRUPT_JSON) {
                System.out.println("  ✓ PASS: checkCloudGeneration() strictly rejects malformed manifest (CORRUPT_JSON)");
            } else {
                System.err.println("  ✗ FAIL: checkCloudGeneration() accepted malformed manifest: " + corruptGenResult.status());
                failures++;
            }

            // Case 18: Fail-Closed isCloudLocked() on Empty Lock Payload
            // An empty lock file listed as present is indeterminate/corrupt, NOT unlocked.
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("   ");
            boolean lockedOnEmpty = ServerLauncher.isCloudLocked(null, mockRclone, b2Target, 12);
            if (lockedOnEmpty) {
                System.out.println("  ✓ PASS: isCloudLocked() FAILS CLOSED (evaluates as locked) on empty lock payload");
            } else {
                System.err.println("  ✗ FAIL: isCloudLocked() evaluated empty lock payload as unlocked!");
                failures++;
            }

            // Case 19: Fractional generation value rejected as CORRUPT_JSON
            MockRcloneHarness.reset();
            MockRcloneHarness.setManifestJson("{\"generation\":26.5,\"last_host\":\"Node\",\"min_orchestrator_version\":\"2.1.10\"}");
            ServerLauncher.CloudGenerationResult fractionalGenResult = ServerLauncher.checkCloudGeneration(null, mockRclone, b2Target);
            if (fractionalGenResult.status() == ServerLauncher.CloudGenerationResult.Status.CORRUPT_JSON) {
                System.out.println("  ✓ PASS: checkCloudGeneration() rejects fractional 'generation' field (26.5) as CORRUPT_JSON");
            } else {
                System.err.println("  ✗ FAIL: checkCloudGeneration() accepted fractional generation 26.5: " + fractionalGenResult.status() + " gen=" + fractionalGenResult.generation());
                failures++;
            }

            // Case 20: String generation value rejected as CORRUPT_JSON
            MockRcloneHarness.reset();
            MockRcloneHarness.setManifestJson("{\"generation\":\"26\",\"last_host\":\"Node\",\"min_orchestrator_version\":\"2.1.10\"}");
            ServerLauncher.CloudGenerationResult stringGenResult = ServerLauncher.checkCloudGeneration(null, mockRclone, b2Target);
            if (stringGenResult.status() == ServerLauncher.CloudGenerationResult.Status.CORRUPT_JSON) {
                System.out.println("  ✓ PASS: checkCloudGeneration() rejects string 'generation' field (\"26\") as CORRUPT_JSON");
            } else {
                System.err.println("  ✗ FAIL: checkCloudGeneration() accepted string generation \"26\": " + stringGenResult.status());
                failures++;
            }

            // Case 21: Fail-Closed acquireCloudLock() on Empty Lock Payload
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("   ");
            boolean acquiredOnEmpty = ServerLauncher.acquireCloudLock(null, mockRclone, b2Target, "NewHost", 12);
            if (!acquiredOnEmpty) {
                System.out.println("  ✓ PASS: acquireCloudLock() FAILS CLOSED (aborts acquisition) on empty lock payload");
            } else {
                System.err.println("  ✗ FAIL: acquireCloudLock() acquired lock despite empty remote state!");
                failures++;
            }

            // Case 22: Malformed Boolean released Field in acquireCloudLock()
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("{\"host\":\"NewHost\",\"lock_id\":\"bad-lock\",\"released\":\"false\",\"timestamp\":\"" +
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now()) + "\"}");
            boolean acquiredOnMalformedBoolean = ServerLauncher.acquireCloudLock(null, mockRclone, b2Target, "NewHost", 12);
            if (!acquiredOnMalformedBoolean) {
                System.out.println("  ✓ PASS: acquireCloudLock() FAILS CLOSED on string-valued 'released' field");
            } else {
                System.err.println("  ✗ FAIL: acquireCloudLock() treated malformed 'released' boolean as unlocked!");
                failures++;
            }

            // Case 23: Tombstone Write Failure Retains Ownership
            MockRcloneHarness.reset();
            MockRcloneHarness.setLockJson("{\"host\":\"\",\"lock_id\":\"\",\"released\":true,\"timestamp\":\"1970-01-01T00:00:00Z\"}");
            boolean lockForTombstoneTest = ServerLauncher.acquireCloudLock(null, mockRclone, b2Target, "TombstoneHost", 12);
            String heldLockId = ServerLauncher.getActiveLockId();
            if (lockForTombstoneTest && heldLockId != null && !heldLockId.isEmpty()) {
                MockRcloneHarness.setRule("lockWriteExitCode", "31");
                boolean tombstoneReleaseFailed = ServerLauncher.forceReleaseCloudLock(null, mockRclone, b2Target);
                if (tombstoneReleaseFailed == false && heldLockId.equals(ServerLauncher.getActiveLockId())) {
                    System.out.println("  ✓ PASS: Failed force tombstone retained active lock ownership");
                } else {
                    System.err.println("  ✗ FAIL: Failed tombstone cleared ownership or reported success!");
                    failures++;
                }
            } else {
                System.err.println("  ✗ FAIL: Could not establish lock for tombstone failure test!");
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
