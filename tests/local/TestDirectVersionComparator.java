public class TestDirectVersionComparator {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  [DIRECT TEST 5/5] ServerLauncher.compareVersions() SemVer Boundary Tests      ");
        System.out.println("================================================================================");

        int failures = 0;

        // Case 1: Standard minor/patch progression (2.1.12 > 2.1.11)
        int res1 = ServerLauncher.compareVersions("2.1.12", "2.1.11");
        if (res1 > 0) {
            System.out.println("  ✓ PASS: '2.1.12' is correctly evaluated as newer than '2.1.11'");
        } else {
            System.err.printf("  ✗ FAIL: '2.1.12' vs '2.1.11' returned: %d%n", res1);
            failures++;
        }

        // Case 2: Multi-digit vs single-digit patch (2.1.12 > 2.1.2) - prevents alphabetical sort bug
        int res2 = ServerLauncher.compareVersions("2.1.12", "2.1.2");
        if (res2 > 0) {
            System.out.println("  ✓ PASS: '2.1.12' evaluated as newer than '2.1.2' (Numeric parsing enforced)");
        } else {
            System.err.printf("  ✗ FAIL: Numerical comparison failed: '2.1.12' vs '2.1.2' returned: %d%n", res2);
            failures++;
        }

        // Case 3: Version equality
        int res3 = ServerLauncher.compareVersions("2.1.12", "2.1.12");
        if (res3 == 0) {
            System.out.println("  ✓ PASS: '2.1.12' equals '2.1.12'");
        } else {
            System.err.printf("  ✗ FAIL: Identical versions comparison returned: %d%n", res3);
            failures++;
        }

        // Case 4: 'v' prefix tolerance ('v2.1.12' vs '2.1.12')
        int res4 = ServerLauncher.compareVersions("v2.1.12", "2.1.12");
        if (res4 == 0) {
            System.out.println("  ✓ PASS: 'v2.1.12' correctly normalized to match '2.1.12'");
        } else {
            System.err.printf("  ✗ FAIL: 'v' prefix mismatch returned: %d%n", res4);
            failures++;
        }

        // Case 5: Variable component length ('2.1' vs '2.1.0')
        int res5 = ServerLauncher.compareVersions("2.1", "2.1.0");
        if (res5 == 0) {
            System.out.println("  ✓ PASS: '2.1' evaluates equal to '2.1.0' (Implicit zero padding)");
        } else {
            System.err.printf("  ✗ FAIL: Variable length comparison returned: %d%n", res5);
            failures++;
        }

        // Case 6: Major version bump ('3.0.0' > '2.1.12')
        int res6 = ServerLauncher.compareVersions("3.0.0", "2.1.12");
        if (res6 > 0) {
            System.out.println("  ✓ PASS: Major bump '3.0.0' is newer than '2.1.12'");
        } else {
            System.err.printf("  ✗ FAIL: Major bump comparison returned: %d%n", res6);
            failures++;
        }

        // Case 7: Null / Empty safety
        int res7 = ServerLauncher.compareVersions("", "2.1.12");
        int res8 = ServerLauncher.compareVersions(null, "2.1.12");
        if (res7 < 0 && res8 < 0) {
            System.out.println("  ✓ PASS: Null and empty strings fail gracefully as older than valid version");
        } else {
            System.err.printf("  ✗ FAIL: Null/empty handling returned: empty=%d, null=%d%n", res7, res8);
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL DIRECT VERSION COMPARISON ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
