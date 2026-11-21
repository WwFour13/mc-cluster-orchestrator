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

        // 3. Strict RFC 8259 Compliance Tests
        // Trailing comma rejection in strict mode
        boolean strictTrailingCommaFailed = false;
        try {
            MiniJson.parseObjectStrict("{\"key\":\"val\",}");
        } catch (MiniJson.ParseException e) {
            strictTrailingCommaFailed = true;
        }
        if (strictTrailingCommaFailed) {
            System.out.println("  ✓ PASS: MiniJson.parseObjectStrict strictly rejects trailing comma in object");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObjectStrict accepted trailing comma!");
            failures++;
        }

        // Invalid escape sequence rejection in strict mode
        boolean strictEscapeFailed = false;
        try {
            MiniJson.parseObjectStrict("{\"key\":\"val\\x12\"}");
        } catch (MiniJson.ParseException e) {
            strictEscapeFailed = true;
        }
        if (strictEscapeFailed) {
            System.out.println("  ✓ PASS: MiniJson.parseObjectStrict strictly rejects invalid escape sequence ('\\x')");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObjectStrict accepted invalid escape sequence!");
            failures++;
        }

        // Lenient mode tolerates trailing commas
        MiniJson.JsonObject lenientObj = MiniJson.parseObject("{\"key\":\"val\",}");
        if (lenientObj != null && "val".equals(lenientObj.getString("key"))) {
            System.out.println("  ✓ PASS: MiniJson.parseObject leniently tolerates trailing commas in configuration files");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObject failed on lenient trailing comma!");
            failures++;
        }

        // 4. Strict mode rejects uppercase boolean literals (RFC 8259 §3)
        boolean strictUppercaseBoolFailed = false;
        try {
            MiniJson.parseObjectStrict("{\"released\":TRUE}");
        } catch (MiniJson.ParseException e) {
            strictUppercaseBoolFailed = true;
        }
        if (strictUppercaseBoolFailed) {
            System.out.println("  ✓ PASS: MiniJson.parseObjectStrict strictly rejects uppercase 'TRUE' literal");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObjectStrict accepted uppercase 'TRUE'!");
            failures++;
        }

        // 5. Strict mode rejects leading-zero number (RFC 8259 §6)
        boolean strictLeadingZeroFailed = false;
        try {
            MiniJson.parseObjectStrict("{\"generation\":026}");
        } catch (MiniJson.ParseException e) {
            strictLeadingZeroFailed = true;
        }
        if (strictLeadingZeroFailed) {
            System.out.println("  ✓ PASS: MiniJson.parseObjectStrict strictly rejects leading-zero number (026)");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObjectStrict accepted leading-zero number 026!");
            failures++;
        }

        // 6. Strict mode rejects bare minus with no digits
        boolean strictBareMinus = false;
        try {
            MiniJson.parseObjectStrict("{\"gen\":-}");
        } catch (MiniJson.ParseException e) {
            strictBareMinus = true;
        }
        if (strictBareMinus) {
            System.out.println("  ✓ PASS: MiniJson.parseObjectStrict strictly rejects bare '-' with no digits");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObjectStrict accepted bare '-'!");
            failures++;
        }

        // 7. getStrictInt rejects fractional Double (e.g. generation: 26.5)
        boolean strictIntFractional = false;
        try {
            MiniJson.JsonObject fracObj = MiniJson.parseObjectStrict("{\"generation\":26.5}");
            fracObj.getStrictInt("generation");
        } catch (MiniJson.ParseException e) {
            strictIntFractional = true;
        }
        if (strictIntFractional) {
            System.out.println("  ✓ PASS: JsonObject.getStrictInt() rejects fractional value 26.5 as ParseException");
        } else {
            System.err.println("  ✗ FAIL: JsonObject.getStrictInt() accepted fractional value 26.5!");
            failures++;
        }

        // 8. getStrictInt rejects string field type
        boolean strictIntString = false;
        try {
            MiniJson.JsonObject strObj = MiniJson.parseObjectStrict("{\"generation\":\"26\"}");
            strObj.getStrictInt("generation");
        } catch (MiniJson.ParseException e) {
            strictIntString = true;
        }
        if (strictIntString) {
            System.out.println("  ✓ PASS: JsonObject.getStrictInt() rejects string value \"26\" as ParseException");
        } else {
            System.err.println("  ✗ FAIL: JsonObject.getStrictInt() accepted string value \"26\"!");
            failures++;
        }

        // 9. Lenient mode accepts uppercase TRUE
        MiniJson.JsonObject lenientBool = MiniJson.parseObject("{\"released\":TRUE}");
        if (lenientBool != null && lenientBool.getBoolean("released", false) == true) {
            System.out.println("  ✓ PASS: MiniJson.parseObject (lenient) accepts uppercase 'TRUE' for config compatibility");
        } else {
            System.err.println("  ✗ FAIL: MiniJson.parseObject (lenient) failed on uppercase 'TRUE'!");
            failures++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL JSON EXTRACTION ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
