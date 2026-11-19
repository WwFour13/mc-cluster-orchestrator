import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestRcloneFlagIntegrity {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 2/7] Rclone Command Construction & Flag Integrity                  ");
        System.out.println("================================================================================");

        Path srcFile = Paths.get("src", "ServerLauncher.java");
        if (!Files.exists(srcFile)) {
            System.err.println("  ✗ FAIL: src/ServerLauncher.java not found.");
            System.exit(1);
        }

        String code = Files.readString(srcFile, StandardCharsets.UTF_8);
        int failures = 0;

        // 1. Inspect pullFromB2 block
        String pullMethod = extractMethodBody(code, "public static boolean pullFromB2(Path rootDir, String rclone, String b2Target, int staleMin, boolean force, int knownCloudGen, double knownEstMb)");
        if (pullMethod != null) {
            if (!pullMethod.contains("\"--update\"")) {
                System.out.println("  ✓ PASS: pullFromB2 strictly excludes '--update' (Authoritative cloud sync)");
            } else {
                System.err.println("  ✗ FAIL: pullFromB2 contains '--update', which risks retaining stale local files!");
                failures++;
            }
            if (pullMethod.contains("\"--fast-list\"") && pullMethod.contains("\"--use-server-modtime\"")) {
                System.out.println("  ✓ PASS: pullFromB2 includes '--fast-list' and '--use-server-modtime'");
            } else {
                System.err.println("  ✗ FAIL: pullFromB2 missing fast-list or use-server-modtime optimizations.");
                failures++;
            }
            if (pullMethod.contains("\"--exclude\"") && pullMethod.contains("\"*.zip\"") && pullMethod.contains("\"*.tar*\"")) {
                System.out.println("  ✓ PASS: pullFromB2 strictly enforces hardcoded '--exclude *.zip' CLI guard");
            } else {
                System.err.println("  ✗ FAIL: pullFromB2 missing hardcoded '--exclude *.zip' CLI guard!");
                failures++;
            }
        } else {
            System.err.println("  ✗ FAIL: Could not locate pullFromB2 method in ServerLauncher.java");
            failures++;
        }

        // 2. Inspect syncAdminDistribution block
        String syncAdminMethod = extractMethodBody(code, "public static void syncAdminDistribution");
        if (syncAdminMethod != null) {
            if (!syncAdminMethod.contains("\"--update\"")) {
                System.out.println("  ✓ PASS: syncAdminDistribution strictly excludes '--update' (Authoritative admin sync)");
            } else {
                System.err.println("  ✗ FAIL: syncAdminDistribution contains '--update'!");
                failures++;
            }
        } else {
            System.err.println("  ✗ FAIL: Could not locate syncAdminDistribution method.");
            failures++;
        }

        // 3. Inspect estimateEgressMb block
        String estimateMethod = extractMethodBody(code, "public static double estimateEgressMb(Path rootDir, String rclone, String b2Target, int knownCloudGen)");
        if (estimateMethod != null) {
            if (!estimateMethod.contains("\"--update\"")) {
                System.out.println("  ✓ PASS: estimateEgressMb strictly excludes '--update'");
            } else {
                System.err.println("  ✗ FAIL: estimateEgressMb contains '--update'!");
                failures++;
            }
            if (estimateMethod.contains("\"--dry-run\"")) {
                System.out.println("  ✓ PASS: estimateEgressMb retains '--dry-run' safety guard");
            } else {
                System.err.println("  ✗ FAIL: estimateEgressMb missing '--dry-run'!");
                failures++;
            }
            if (estimateMethod.contains("\"--exclude\"") && estimateMethod.contains("\"*.zip\"") && estimateMethod.contains("\"*.tar*\"")) {
                System.out.println("  ✓ PASS: estimateEgressMb strictly enforces hardcoded '--exclude *.zip' CLI guard");
            } else {
                System.err.println("  ✗ FAIL: estimateEgressMb missing hardcoded '--exclude *.zip' CLI guard!");
                failures++;
            }
        } else {
            System.err.println("  ✗ FAIL: Could not locate estimateEgressMb method.");
            failures++;
        }

        // 4. Inspect pushToB2 block
        String pushMethod = extractMethodBody(code, "public static boolean pushToB2");
        if (pushMethod != null) {
            if (pushMethod.contains("\"--update\"")) {
                System.out.println("  ✓ PASS: pushToB2 retains '--update' to skip unchanged local chunks");
            } else {
                System.err.println("  ✗ FAIL: pushToB2 is missing '--update'!");
                failures++;
            }
            if (pushMethod.contains("\"--s3-no-head\"") && pushMethod.contains("\"--s3-no-head-object\"")) {
                System.out.println("  ✓ PASS: pushToB2 retains '--s3-no-head' and '--s3-no-head-object' (Zero Class B)");
            } else {
                System.err.println("  ✗ FAIL: pushToB2 missing s3-no-head or s3-no-head-object flags!");
                failures++;
            }
            if (pushMethod.contains("\"--fast-list\"")) {
                System.out.println("  ✓ PASS: pushToB2 retains '--fast-list' (Single batch Class C listing)");
            } else {
                System.err.println("  ✗ FAIL: pushToB2 missing fast-list!");
                failures++;
            }
            if (pushMethod.contains("\"--exclude\"") && pushMethod.contains("\"*.zip\"") && pushMethod.contains("\"*.tar*\"")) {
                System.out.println("  ✓ PASS: pushToB2 strictly enforces hardcoded '--exclude *.zip' CLI guard");
            } else {
                System.err.println("  ✗ FAIL: pushToB2 missing hardcoded '--exclude *.zip' CLI guard!");
                failures++;
            }
        } else {
            System.err.println("  ✗ FAIL: Could not locate pushToB2 method.");
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 10 FLAG INTEGRITY ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }

    private static String extractMethodBody(String source, String methodSignature) {
        int idx = source.indexOf(methodSignature);
        if (idx == -1) return null;
        int startBrace = source.indexOf("{", idx);
        if (startBrace == -1) return null;

        int depth = 1;
        for (int i = startBrace + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(startBrace, i + 1);
                }
            }
        }
        return null;
    }
}
