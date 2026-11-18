import java.util.UUID;

public class TestCredentialIsolation {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 1/7] Credential Isolation & B2 Remote Target Integrity            ");
        System.out.println("================================================================================");

        int failures = 0;

        // Dynamic or CLI-supplied test credentials (zero hardcoded secrets)
        String bucket = args.length > 0 ? args[0] : "mock-bucket-" + UUID.randomUUID().toString().substring(0, 8);
        String keyId = args.length > 1 ? args[1] : "mockkey" + UUID.randomUUID().toString().replace("-", "");
        String secretKey = args.length > 2 ? args[2] : "mocksecret" + UUID.randomUUID().toString().replace("-", "");
        String customEndpoint = args.length > 3 ? args[3] : "s3.us-west-004.backblazeb2.com";

        // 1. Generate remote with custom endpoint
        String remote = ServerLauncher.getB2Remote(bucket, keyId, secretKey, customEndpoint);

        // Assertion 1: Clean remote string format
        String expectedRemote = "CLUSTER:" + bucket + "/server";
        if (expectedRemote.equals(remote)) {
            System.out.printf("  ✓ PASS: Remote target formatted cleanly as '%s'%n", expectedRemote);
        } else {
            System.err.println("  ✗ FAIL: Unexpected remote string format: " + remote);
            failures++;
        }

        // Assertion 2: No secrets in remote target string
        if (!remote.contains(keyId) && !remote.contains(secretKey)) {
            System.out.println("  ✓ PASS: Remote string is 100% free of embedded credential tokens");
        } else {
            System.err.println("  ✗ FAIL: Secrets leaked into remote target string: " + remote);
            failures++;
        }

        // Assertion 3: Environment map isolation
        String envKey = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_ACCESS_KEY_ID");
        String envSecret = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_SECRET_ACCESS_KEY");
        String envEndpoint = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_ENDPOINT");
        String envType = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_TYPE");

        if (keyId.equals(envKey) && secretKey.equals(envSecret) && customEndpoint.equals(envEndpoint) && "s3".equals(envType)) {
            System.out.println("  ✓ PASS: Credentials correctly isolated in RCLONE_ENV map");
        } else {
            System.err.println("  ✗ FAIL: RCLONE_ENV contains incorrect credential mappings");
            failures++;
        }

        // Assertion 4: Stripped no_head_object to fix single-file rclone cat
        String envNoHeadObj = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_NO_HEAD_OBJECT");
        if (envNoHeadObj == null || !Boolean.parseBoolean(envNoHeadObj)) {
            System.out.println("  ✓ PASS: no_head_object correctly excluded from connection config (cat works)");
        } else {
            System.err.println("  ✗ FAIL: no_head_object was found in connection config!");
            failures++;
        }

        // Assertion 5: Preserved no_head and no_check_bucket for zero-Class-B bucket probing
        String envNoHead = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_NO_HEAD");
        String envNoCheckBucket = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_NO_CHECK_BUCKET");
        if ("true".equalsIgnoreCase(envNoHead) && "true".equalsIgnoreCase(envNoCheckBucket)) {
            System.out.println("  ✓ PASS: no_head and no_check_bucket preserved to prevent bucket HEAD calls");
        } else {
            System.err.println("  ✗ FAIL: Missing required no_head or no_check_bucket optimizations");
            failures++;
        }

        // Assertion 6: Automatic endpoint calculation from cluster ID prefix (004 vs 005)
        String dynamic004Key = "004" + UUID.randomUUID().toString().replace("-", "");
        String dynamic005Key = "005" + UUID.randomUUID().toString().replace("-", "");
        ServerLauncher.getB2Remote(bucket, dynamic004Key, secretKey, null);
        String endpoint004 = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_ENDPOINT");
        ServerLauncher.getB2Remote(bucket, dynamic005Key, secretKey, null);
        String endpoint005 = ServerLauncher.RCLONE_ENV.get("RCLONE_CONFIG_CLUSTER_ENDPOINT");

        if ("s3.us-west-004.backblazeb2.com".equals(endpoint004) && "s3.us-east-005.backblazeb2.com".equals(endpoint005)) {
            System.out.println("  ✓ PASS: Region endpoints auto-resolved from key prefix (004 -> us-west-004, 005 -> us-east-005)");
        } else {
            System.err.printf("  ✗ FAIL: Unexpected calculated endpoints: 004=%s, 005=%s%n", endpoint004, endpoint005);
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 6 ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
