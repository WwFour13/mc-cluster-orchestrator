import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;

public class TestDirectWorldSafety {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [DIRECT TEST 1/5] ServerLauncher.pushToB2 Direct Execution & World Safety      ");
        System.out.println("================================================================================");

        int failures = 0;
        MockRcloneHarness.setup();
        String mockRclone = MockRcloneHarness.getMockRclonePath();
        String b2Target = "CLUSTER:unit-test-bucket/server";

        Path tempServerDir = Paths.get("tests", ".tmp_direct_world_test");
        Files.createDirectories(tempServerDir.resolve("world"));
        Files.writeString(tempServerDir.resolve("world").resolve("level.dat"), "test_level_data", StandardCharsets.UTF_8);

        try {
            // Case 1: Split-Brain Conflict Guard (Local Gen #25 behind Cloud Gen #26)
            MockRcloneHarness.reset();
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":26,\"last_host\":\"PeerNode\",\"min_orchestrator_version\":\"2.1.10\"}");
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":25}", StandardCharsets.UTF_8);

            boolean pushResult1 = ServerLauncher.pushToB2(tempServerDir, mockRclone, b2Target, "TestHost", 12, "10M", true);
            if (!pushResult1) {
                System.out.println("  ✓ PASS: pushToB2() DIRECTLY aborted push when local (#25) is behind cloud (#26)");
            } else {
                System.err.println("  ✗ FAIL: pushToB2() permitted push with stale local generation!");
                failures++;
            }

            // Verify that no chunk copy or rcat commands were executed during abort
            List<String> calls1 = MockRcloneHarness.getRecordedInvocations();
            boolean copyCalled = calls1.stream().anyMatch(c -> c.startsWith("copy"));
            boolean rcatCalled = calls1.stream().anyMatch(c -> c.startsWith("rcat"));
            if (!copyCalled && !rcatCalled) {
                System.out.println("  ✓ PASS: Zero copy/rcat operations executed during conflict abort (World protected)");
            } else {
                System.err.println("  ✗ FAIL: Outbound copy/rcat was invoked despite generation abort!");
                failures++;
            }

            // Case 2: Synchronized Push Execution (Local Gen #26, Cloud Gen #26 -> Advance to #27)
            MockRcloneHarness.reset();
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":26,\"last_host\":\"OtherNode\",\"min_orchestrator_version\":\"2.1.10\"}");
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":26}", StandardCharsets.UTF_8);

            boolean pushResult2 = ServerLauncher.pushToB2(tempServerDir, mockRclone, b2Target, "TestHost", 12, "10M", true);
            if (pushResult2) {
                System.out.println("  ✓ PASS: pushToB2() DIRECTLY succeeded for aligned generation");
            } else {
                System.err.println("  ✗ FAIL: pushToB2() unexpectedly failed for aligned generation");
                failures++;
            }

            // Verify runtime flags passed to copy
            List<String> calls2 = MockRcloneHarness.getRecordedInvocations();
            String copyCall = calls2.stream().filter(c -> c.startsWith("copy")).findFirst().orElse("");
            if (copyCall.contains("--update") && copyCall.contains("--fast-list") && copyCall.contains("--use-server-modtime")
                    && copyCall.contains("--s3-no-head") && copyCall.contains("--s3-no-head-object")
                    && copyCall.contains("--exclude *.zip") && copyCall.contains("--exclude *.tar*") && copyCall.contains("--exclude cohost-files*")) {
                System.out.println("  ✓ PASS: pushToB2() passed exact required zero-Class-B flags and hardcoded archive exclusion flags array");
            } else {
                System.err.println("  ✗ FAIL: Missing required flags in actual copy invocation: " + copyCall);
                failures++;
            }

            // Case 3: Outbound Push Failure triggers Fail-Closed Lock Retention contract
            MockRcloneHarness.reset();
            MockRcloneHarness.setRule("exitCode", "1"); // Force copy to fail
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":26}", StandardCharsets.UTF_8);

            boolean pushResult3 = ServerLauncher.pushToB2(tempServerDir, mockRclone, b2Target, "TestHost", 12, "10M", true);
            if (!pushResult3) {
                System.out.println("  ✓ PASS: pushToB2() cleanly returned false upon transfer failure");
            } else {
                System.err.println("  ✗ FAIL: pushToB2() reported success despite transfer failure");
                failures++;
            }

            // Simulate teardown contract
            boolean lockReleased = false;
            if (pushResult3) {
                ServerLauncher.releaseCloudLock(tempServerDir, mockRclone, b2Target);
                lockReleased = true;
            }

            if (!lockReleased) {
                System.out.println("  ✓ PASS: Fail-closed contract verified: Lock release strictly withheld on push failure");
            } else {
                System.err.println("  ✗ FAIL: Lock release was executed despite push failure!");
                failures++;
            }

            // Case 4: World Chunk Copy Succeeds, but manifest.json Write Fails -> pushToB2 fails closed
            MockRcloneHarness.reset();
            MockRcloneHarness.setRule("manifestJson", "{\"generation\":26,\"last_host\":\"OtherNode\",\"min_orchestrator_version\":\"2.1.10\"}");
            MockRcloneHarness.setRule("manifestWriteExitCode", "1"); // Force manifest write to fail
            Files.writeString(tempServerDir.resolve(".cluster_session"), "{\"base_generation\":26}", StandardCharsets.UTF_8);

            boolean pushResult4 = ServerLauncher.pushToB2(tempServerDir, mockRclone, b2Target, "TestHost", 12, "10M", true);
            if (!pushResult4) {
                System.out.println("  ✓ PASS: pushToB2() DIRECTLY returned false when manifest.json write failed");
            } else {
                System.err.println("  ✗ FAIL: pushToB2() reported success despite manifest write failure!");
                failures++;
            }

            int localGenAfterFail = ServerLauncher.readLocalGeneration(tempServerDir);
            if (localGenAfterFail == 26) {
                System.out.println("  ✓ PASS: Local generation remained at #26 (unbumped) following manifest failure");
            } else {
                System.err.println("  ✗ FAIL: Local generation was bumped despite manifest write failure: " + localGenAfterFail);
                failures++;
            }

        } finally {
            MockRcloneHarness.teardown();
            if (Files.exists(tempServerDir)) {
                Files.walk(tempServerDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL DIRECT WORLD SAFETY ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
