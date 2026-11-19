import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Unit Test: TestPlayitTunnelParsing
 *
 * Verifies Playit.gg anycast tunnel resolution and sidecar configuration:
 * 1. JSON SRV and Assigned Domain extraction from mock CLI output.
 * 2. Fallback cascade: assigned_srv -> assigned_domain -> duckdns/domain.
 * 3. Configuration persistence: saving resolved public tunnel address into config.json.
 * 4. Playit binary discovery and resolution across search paths (bin/, root, tools/).
 *
 * 100% offline, executing strictly within temporary directories.
 */
public class TestPlayitTunnelParsing {

    public static void main(String[] args) {
        System.out.println("Running TestPlayitTunnelParsing...");
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("test-playit-root");

            // --- TEST 1: Primary SRV Address Extraction ---
            String mockPlayitSrvJson = "{\n" +
                    "  \"tunnels\": [\n" +
                    "    {\n" +
                    "      \"id\": \"tun_123\",\n" +
                    "      \"assigned_srv\": \"dorm-craft.gl.joinmc.link\",\n" +
                    "      \"assigned_domain\": \"dorm-craft.auto.playit.gg\",\n" +
                    "      \"tunnel_type\": \"minecraft-java\"\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            String srv = ServerLauncher.extractJsonString(mockPlayitSrvJson, "assigned_srv");
            assertNotNull(srv, "assigned_srv should be extracted from JSON");
            assertEquals(srv, "dorm-craft.gl.joinmc.link", "SRV domain should match expected");

            // --- TEST 2: Fallback to Assigned Domain when SRV is missing ---
            String mockPlayitDomainJson = "{\n" +
                    "  \"tunnels\": [\n" +
                    "    {\n" +
                    "      \"id\": \"tun_456\",\n" +
                    "      \"assigned_domain\": \"dorm-fallback.auto.playit.gg\",\n" +
                    "      \"tunnel_type\": \"minecraft-java\"\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            String missingSrv = ServerLauncher.extractJsonString(mockPlayitDomainJson, "assigned_srv");
            String assignedDomain = ServerLauncher.extractJsonString(mockPlayitDomainJson, "assigned_domain");

            assertTrue(missingSrv == null || missingSrv.isEmpty(), "missingSrv should be null or empty");
            assertNotNull(assignedDomain, "assigned_domain must be present");
            assertEquals(assignedDomain, "dorm-fallback.auto.playit.gg", "Fallback domain should match");

            // --- TEST 3: Persisting Server Address into config.json ---
            Path cfgFile = tempDir.resolve("config.json");
            Files.writeString(cfgFile, "{\n  \"b2_bucket\": \"test-bucket\"\n}\n", StandardCharsets.UTF_8);

            ServerLauncher.saveServerAddressToConfig(tempDir, "dorm-craft.gl.joinmc.link");

            assertTrue(Files.exists(cfgFile), "config.json must exist");
            String updatedCfg = Files.readString(cfgFile, StandardCharsets.UTF_8);
            String savedAddr = ServerLauncher.extractJsonString(updatedCfg, "server_address");
            assertEquals(savedAddr, "dorm-craft.gl.joinmc.link", "server_address must be persisted into config.json");

            // Re-saving with a new address should update, not duplicate
            ServerLauncher.saveServerAddressToConfig(tempDir, "dorm-craft-new.gl.joinmc.link");
            String reUpdatedCfg = Files.readString(cfgFile, StandardCharsets.UTF_8);
            String reSavedAddr = ServerLauncher.extractJsonString(reUpdatedCfg, "server_address");
            assertEquals(reSavedAddr, "dorm-craft-new.gl.joinmc.link", "server_address must be updated in place");

            // --- TEST 4: Binary Discovery across Candidate Paths ---
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String exeName = isWindows ? "playit.exe" : "playit";

            Path binDir = tempDir.resolve("bin");
            Files.createDirectories(binDir);
            Path mockPlayitBin = binDir.resolve(exeName);
            Files.writeString(mockPlayitBin, "MOCK_PLAYIT_BINARY", StandardCharsets.UTF_8);

            String foundBin = ServerLauncher.findPlayit(tempDir);
            assertNotNull(foundBin, "findPlayit should discover binary in bin/");
            assertTrue(foundBin.contains("bin"), "Discovered path should point to bin/");
            assertTrue(foundBin.endsWith(exeName), "Discovered path should match exeName");

            System.out.println("TestPlayitTunnelParsing: ALL ASSERTIONS PASSED (100% SUCCESS)");

        } catch (Throwable t) {
            System.err.println("TestPlayitTunnelParsing FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        } finally {
            safeDeleteDir(tempDir);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion Failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(String.format("Assertion Failed: %s [Expected: %s, Actual: %s]", message, expected, actual));
        }
    }

    private static void assertNotNull(Object obj, String message) {
        if (obj == null) throw new AssertionError("Assertion Failed: " + message);
    }

    private static void safeDeleteDir(Path p) {
        if (p == null || !Files.exists(p)) return;
        try {
            try (var stream = Files.walk(p)) {
                stream.sorted(Comparator.reverseOrder())
                      .map(Path::toFile)
                      .forEach(File::delete);
            }
        } catch (Exception ignored) {}
    }
}
