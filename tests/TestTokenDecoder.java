import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

public class TestTokenDecoder {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("        MC CLUSTER ORCHESTRATOR - TOKEN DIAGNOSTIC & B2 CLOUD PROBE             ");
        System.out.println("================================================================================");

        Path rootDir = resolveServerRoot();
        System.out.println("[Environment] Server Root: " + rootDir.toAbsolutePath());

        String tokenInput = "";
        if (args.length > 0 && !args[0].trim().isEmpty()) {
            tokenInput = args[0].trim();
            System.out.println("[Input] Received token via command-line arguments.");
        } else {
            try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
                System.out.print("\nPaste your mc-cluster:// token (or press Enter to test config.json): ");
                if (scanner.hasNextLine()) {
                    tokenInput = scanner.nextLine();
                }
            }
        }

        if (tokenInput == null || tokenInput.trim().isEmpty()) {
            System.out.println("[INFO] No input entered. Generating active token from minecraft-server/config.json...");
            tokenInput = generateTokenFromLocalConfig(rootDir);
            if (tokenInput == null || tokenInput.isEmpty()) {
                System.err.println("[ERROR] No config.json found to generate a fallback token. Exiting.");
                return;
            }
            System.out.println("[INFO] Generated Baseline Token: " + tokenInput);
        }

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println(" 1. RAW STRING & HIDDEN CHARACTER SCANNER");
        System.out.println("--------------------------------------------------------------------------------");
        inspectRawCharacters(tokenInput);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println(" 2. BASE64 DECODING & PRODUCTION DECODER VERIFICATION");
        System.out.println("--------------------------------------------------------------------------------");
        ClusterToken.ClusterConfig config = testDecode(tokenInput);

        if (config == null) {
            System.err.println("\n[FATAL] Token decoding returned NULL! Production ClusterToken.decode() rejected this token.");
            return;
        }

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println(" 3. EXTRACTED & SANITIZED CONFIGURATION FIELDS");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("  * Bucket Name        : %s%n", config.bucket());
        System.out.printf("  * Application Key ID : %s (Length: %d chars | Alphanumeric: %s)%n",
                config.keyId(), config.keyId().length(), config.keyId().matches("^[a-zA-Z0-9]+$") ? "YES [CLEAN]" : "NO [DIRTY]");
        System.out.printf("  * Application Key    : %s (Length: %d chars)%n", maskKey(config.appKey()), config.appKey().length());
        System.out.printf("  * Stale Timeout (Min): %d minutes%n", config.staleMinutes());
        System.out.printf("  * Bandwidth Limit    : %s%n", config.bandwidthLimit());
        System.out.printf("  * Public Domain / IP : %s%n", config.domain() != null ? config.domain() : "(None provided)");

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println(" 4. LIVE BACKBLAZE B2 AUTHENTICATION & MANIFEST PROBE");
        System.out.println("--------------------------------------------------------------------------------");
        testCloudConnectivityAndManifest(rootDir, config);

        System.out.println("\n================================================================================");
        System.out.println(" [TEST COMPLETE] Diagnostics finished.");
        System.out.println("================================================================================");
    }

    private static void inspectRawCharacters(String raw) {
        System.out.printf("  * String Length: %d characters%n", raw.length());

        List<String> anomalies = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int code = (int) c;

            if (code == 0x200B) anomalies.add(String.format("Zero-Width Space (\\u200B) at index %d", i));
            else if (code == 0x200C) anomalies.add(String.format("Zero-Width Non-Joiner (\\u200C) at index %d", i));
            else if (code == 0x200D) anomalies.add(String.format("Zero-Width Joiner (\\u200D) at index %d", i));
            else if (code == 0xFEFF) anomalies.add(String.format("Byte Order Mark (\\uFEFF) at index %d", i));
            else if (code == '\r') anomalies.add(String.format("Carriage Return (\\r) at index %d", i));
            else if (code == '\n') anomalies.add(String.format("Newline (\\n) at index %d", i));
            else if (code == '\t') anomalies.add(String.format("Tab (\\t) at index %d", i));
            else if (code == '`') anomalies.add(String.format("Discord Markdown Backtick (`) at index %d", i));
            else if (code == '“' || code == '”' || code == '‘' || code == '’') anomalies.add(String.format("Smart Quote (%c) at index %d", c, i));
            else if (code < 32 || (code > 126 && code < 160)) anomalies.add(String.format("Control character (code %d) at index %d", code, i));
            else if (code > 127) anomalies.add(String.format("Non-ASCII character '%c' (\\u%04X) at index %d", c, code, i));
        }

        if (anomalies.isEmpty()) {
            System.out.println("  [OK] Character scanner clean: zero invisible characters, zero smart quotes, zero unescaped line breaks.");
        } else {
            System.err.printf("  [WARN] Found %d formatting artifact(s) in input string:%n", anomalies.size());
            for (String a : anomalies) {
                System.err.println("    - " + a);
            }
        }
    }

    private static ClusterToken.ClusterConfig testDecode(String rawToken) {
        String clean = rawToken.trim();
        if (clean.startsWith("```") && clean.endsWith("```")) {
            System.out.println("  [Fixup] Detected Discord code block. Stripping triple backticks...");
            clean = clean.substring(3, clean.length() - 3).trim();
        } else if (clean.startsWith("`") && clean.endsWith("`")) {
            System.out.println("  [Fixup] Detected Discord inline code. Stripping backticks...");
            clean = clean.substring(1, clean.length() - 1).trim();
        }

        if (!clean.startsWith(ClusterToken.PREFIX)) {
            System.err.printf("  [WARN] Token does not start with prefix '%s'.%n", ClusterToken.PREFIX);
        }

        String payload = clean;
        if (clean.startsWith(ClusterToken.PREFIX)) {
            payload = clean.substring(ClusterToken.PREFIX.length()).trim();
        }

        try {
            byte[] bytes;
            try {
                bytes = Base64.getUrlDecoder().decode(payload);
                System.out.println("  [OK] Base64 URL-safe decode successful.");
            } catch (IllegalArgumentException ex) {
                System.out.println("  [Fallback] Standard Base64 decode attempting...");
                bytes = Base64.getDecoder().decode(payload);
                System.out.println("  [OK] Standard Base64 decode successful.");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            System.out.println("  [Payload JSON Preview]:");
            System.out.println("    " + json);
        } catch (Exception ex) {
            System.err.println("  [ERROR] Failed to decode raw Base64 payload: " + ex.getMessage());
        }

        System.out.println("\n  Testing production ClusterToken.decode()...");
        long t0 = System.nanoTime();
        ClusterToken.ClusterConfig config = ClusterToken.decode(clean);
        long elapsedUs = (System.nanoTime() - t0) / 1000;

        if (config != null) {
            System.out.printf("  [OK] ClusterToken.decode() PASSED (%d microseconds)%n", elapsedUs);
        } else {
            System.err.println("  [FAIL] ClusterToken.decode() returned null.");
        }
        return config;
    }

    private static void testCloudConnectivityAndManifest(Path rootDir, ClusterToken.ClusterConfig config) {
        String rclone = ServerLauncher.findRclone(rootDir);
        if (rclone == null) {
            System.err.println("  [ERROR] rclone executable not found. Cannot perform live B2 authentication check.");
            return;
        }
        System.out.println("  * Rclone Binary: " + rclone);

        String b2Target = ServerLauncher.getB2Remote(config.bucket(), config.keyId(), config.appKey(), null);
        System.out.println("  * B2 Remote Target: " + maskB2Target(b2Target));

        System.out.println("\n  [1/2] Probing Backblaze B2 root connectivity ('rclone lsd')...");
        ServerLauncher.CommandResult lsdRes = ServerLauncher.runCommand(15, rootDir, rclone, "lsd", b2Target);
        if (lsdRes.exitCode() == 0) {
            System.out.println("  [SUCCESS] Backblaze B2 authentication verified! Status: 200 OK.");
        } else {
            System.err.println("  [FAILED] Backblaze B2 authentication check failed (Exit code " + lsdRes.exitCode() + "):");
            if (!lsdRes.stderr().isEmpty()) {
                System.err.println("    STDERR: " + lsdRes.stderr());
            }
            if (!lsdRes.stdout().isEmpty()) {
                System.err.println("    STDOUT: " + lsdRes.stdout());
            }
            return;
        }

        System.out.println("\n  [2/2] Inspecting cluster 'manifest.json' from cloud...");
        ServerLauncher.CommandResult catRes = ServerLauncher.runCommand(15, rootDir, rclone, "cat", b2Target + "/manifest.json");
        if (catRes.exitCode() == 0 && !catRes.stdout().isEmpty()) {
            String mJson = catRes.stdout().trim();
            int cloudGen = ServerLauncher.extractJsonInt(mJson, "generation", -1);
            String cloudHost = ServerLauncher.extractJsonString(mJson, "last_host");
            String cloudSync = ServerLauncher.extractJsonString(mJson, "last_sync");
            String cloudVer = ServerLauncher.extractJsonString(mJson, "orchestrator_version");

            System.out.println("  [Cloud Manifest Detected]:");
            System.out.printf("    • Active World Generation : #%d%n", cloudGen);
            System.out.printf("    • Last Synchronizing Host : %s%n", cloudHost);
            System.out.printf("    • Timestamp               : %s%n", cloudSync);
            System.out.printf("    • Orchestrator Version    : v%s%n", cloudVer);

            int localGen = ServerLauncher.readLocalGeneration(rootDir);
            System.out.printf("    • Local Base Generation   : #%d%n", localGen);
            if (localGen == cloudGen) {
                System.out.println("  [SYNC STATUS] Local storage is 100% IN-SYNC with cloud generation.");
            } else if (localGen < cloudGen) {
                System.out.printf("  [SYNC STATUS] Behind cloud by %d generation(s). Normal pull on startup will synchronize.%n", cloudGen - localGen);
            } else {
                System.out.println("  [SYNC STATUS] Local generation is ahead of cloud.");
            }
        } else {
            System.out.println("  [INFO] No manifest.json found in bucket (Fresh bucket ready for initialization).");
        }
    }

    private static String maskKey(String key) {
        if (key == null) return "null";
        if (key.length() <= 8) return "********";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static String maskB2Target(String target) {
        if (target == null) return "";
        return target.replaceAll("key=[^,]+", "key=********");
    }

    private static Path resolveServerRoot() {
        Path p = Paths.get("minecraft-server");
        if (Files.isDirectory(p)) return p;
        p = Paths.get(".");
        return p;
    }

    private static String generateTokenFromLocalConfig(Path rootDir) {
        try {
            Path cfg = rootDir.resolve("config.json");
            if (!Files.exists(cfg)) return null;
            String json = Files.readString(cfg);
            String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
            String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
            String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
            int stale = ServerLauncher.extractJsonInt(json, "stale_timeout_minutes", 10);
            String bw = ServerLauncher.extractJsonString(json, "b2_bandwidth_limit");
            String domain = ServerLauncher.extractJsonString(json, "server_address");
            if (domain.isEmpty()) domain = ServerLauncher.extractJsonString(json, "domain");
            String token = ServerLauncher.extractJsonString(json, "token");

            ClusterToken.ClusterConfig config = new ClusterToken.ClusterConfig(bucket, keyId, appKey, stale, bw, domain, token);
            return ClusterToken.encode(config);
        } catch (Exception e) {
            return null;
        }
    }
}
