public class TestJsonParsing {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  [UNIT TEST 3/7] JSON Parsing & Boolean Extraction Verification                ");
        System.out.println("================================================================================");

        int failures = 0;

        // 1. Boolean Extraction Tests
        String json1 = "{\"host\":\"ClusterNode-Alpha\",\"released\":true,\"generation\":26}";
        String json2 = "{\"host\":\"ClusterNode-Alpha\",\"released\":false,\"generation\":26}";
        String json3 = "{\"host\":\"ClusterNode-Alpha\",\"released\": TRUE }";
        String json4 = "{\"host\":\"ClusterNode-Alpha\",\"released\": FALSE }";
        String json5 = "{\"host\":\"ClusterNode-Alpha\",\"generation\":26}"; // missing key
        String jsonMalformed = "{malformed-json-content}";

        if (ServerLauncher.extractJsonBoolean(json1, "released", false) == true) {
            System.out.println("  ✓ PASS: extractJsonBoolean parses standard lowercase 'true'");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean failed on standard lowercase 'true'");
            failures++;
        }

        if (ServerLauncher.extractJsonBoolean(json2, "released", true) == false) {
            System.out.println("  ✓ PASS: extractJsonBoolean parses standard lowercase 'false'");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean failed on standard lowercase 'false'");
            failures++;
        }

        if (ServerLauncher.extractJsonBoolean(json3, "released", false) == true) {
            System.out.println("  ✓ PASS: extractJsonBoolean handles uppercase 'TRUE' with whitespace");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean failed on uppercase 'TRUE'");
            failures++;
        }

        if (ServerLauncher.extractJsonBoolean(json4, "released", true) == false) {
            System.out.println("  ✓ PASS: extractJsonBoolean handles uppercase 'FALSE' with whitespace");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean failed on uppercase 'FALSE'");
            failures++;
        }

        if (ServerLauncher.extractJsonBoolean(json5, "released", true) == true
                && ServerLauncher.extractJsonBoolean(json5, "released", false) == false) {
            System.out.println("  ✓ PASS: extractJsonBoolean falls back to provided default on missing key");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean did not honor fallback defaults");
            failures++;
        }

        if (ServerLauncher.extractJsonBoolean(jsonMalformed, "released", true) == true
                && ServerLauncher.extractJsonBoolean(null, "released", false) == false) {
            System.out.println("  ✓ PASS: extractJsonBoolean handles null and malformed payloads safely");
        } else {
            System.err.println("  ✗ FAIL: extractJsonBoolean failed on null or malformed payload");
            failures++;
        }

        // 2. String, Int, and Long Extraction Tests
        String payload = "{\"host\": \"Node-Alpha\", \"timeout\": 12, \"bytes\": 10737418240}";
        if ("Node-Alpha".equals(ServerLauncher.extractJsonString(payload, "host"))) {
            System.out.println("  ✓ PASS: extractJsonString correctly parses string with whitespace");
        } else {
            System.err.println("  ✗ FAIL: extractJsonString failed on 'host'");
            failures++;
        }

        if (ServerLauncher.extractJsonInt(payload, "timeout", 0) == 12) {
            System.out.println("  ✓ PASS: extractJsonInt correctly extracts integer value (12)");
        } else {
            System.err.println("  ✗ FAIL: extractJsonInt failed on 'timeout'");
            failures++;
        }

        if (ServerLauncher.extractJsonLong(payload, "bytes", 0L) == 10737418240L) {
            System.out.println("  ✓ PASS: extractJsonLong correctly extracts 64-bit long integer (10 GB)");
        } else {
            System.err.println("  ✗ FAIL: extractJsonLong failed on 'bytes'");
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL 9 JSON EXTRACTION ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
