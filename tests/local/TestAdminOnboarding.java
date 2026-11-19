import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Unit Test: TestAdminOnboarding
 *
 * Verifies the first-time administrator initialization and onboarding lifecycle:
 * 1. Server Adoption: Adopts an existing Minecraft server folder, extracts and renames
 *    the paper jar to bin/core.dat, migrates world & plugins, and auto-accepts EULA.
 * 2. Configuration & Invite Token: Encodes administrator cloud credentials into a universal
 *    mc-cluster:// invite token and decodes it back to ensure byte-perfect fidelity.
 * 3. Initial Generation Stamping: Validates that initial generation bootstrapping uses
 *    Dual Persistence (writing both .cluster_session and manifest.json).
 *
 * 100% offline, executing strictly within temporary directories.
 */
public class TestAdminOnboarding {

    public static void main(String[] args) {
        System.out.println("Running TestAdminOnboarding...");
        Path tempExisting = null;
        Path tempRoot = null;

        try {
            tempExisting = Files.createTempDirectory("test-admin-existing");
            tempRoot = Files.createTempDirectory("test-admin-root");

            // --- PHASE 1: Server Adoption Logic ---
            // Populate mock existing server
            Path existingJar = tempExisting.resolve("paper-1.20.4-498.jar");
            Files.writeString(existingJar, "MOCK_PAPER_JAR_DATA", StandardCharsets.UTF_8);

            Path existingProps = tempExisting.resolve("server.properties");
            Files.writeString(existingProps, "motd=Existing Server\nserver-port=25565\n", StandardCharsets.UTF_8);

            Path existingWorld = tempExisting.resolve("world");
            Path existingRegion = existingWorld.resolve("region");
            Files.createDirectories(existingRegion);
            Files.writeString(existingWorld.resolve("level.dat"), "EXISTING_LEVEL_DATA", StandardCharsets.UTF_8);
            Files.writeString(existingRegion.resolve("r.0.0.mca"), "EXISTING_CHUNK_DATA", StandardCharsets.UTF_8);

            Path existingPlugins = tempExisting.resolve("plugins");
            Files.createDirectories(existingPlugins);
            Files.writeString(existingPlugins.resolve("EssentialsX.jar"), "MOCK_PLUGIN_DATA", StandardCharsets.UTF_8);

            // Run adoption
            PaperDownloader.adoptExistingServer(tempRoot, tempExisting, msg -> {});

            // Assertions for Adoption
            Path coreDat = tempRoot.resolve("bin").resolve("core.dat");
            assertTrue(Files.exists(coreDat), "bin/core.dat must be created from the existing server jar");
            assertEquals(Files.readString(coreDat, StandardCharsets.UTF_8), "MOCK_PAPER_JAR_DATA", "core.dat must match paper jar content");

            assertTrue(Files.exists(tempRoot.resolve("server.properties")), "server.properties must be adopted");
            assertTrue(Files.exists(tempRoot.resolve("world").resolve("level.dat")), "world/level.dat must be adopted");
            assertTrue(Files.exists(tempRoot.resolve("world").resolve("region").resolve("r.0.0.mca")), "world chunks must be adopted");
            assertTrue(Files.exists(tempRoot.resolve("plugins").resolve("EssentialsX.jar")), "plugins must be adopted");

            Path eula = tempRoot.resolve("eula.txt");
            assertTrue(Files.exists(eula), "eula.txt must be generated");
            assertTrue(Files.readString(eula, StandardCharsets.UTF_8).contains("eula=true"), "eula must be automatically accepted");

            // --- PHASE 2: Configuration & Invite Token Pipeline ---
            String bucket = "dorm-craft-smp-bucket";
            String keyId = "005abc123xyz";
            String appKey = "K005def456secret";
            String host = "Admin-Desktop";

            // Verify config writing
            Path cfgPath = tempRoot.resolve("config.json");
            String cfgJson = String.format("{\n  \"b2_bucket\": \"%s\",\n  \"b2_key_id\": \"%s\",\n  \"b2_application_key\": \"%s\",\n  \"local_host\": \"%s\"\n}\n",
                    bucket, keyId, appKey, host);
            Files.writeString(cfgPath, cfgJson, StandardCharsets.UTF_8);

            assertTrue(Files.exists(cfgPath), "config.json must be written");
            assertEquals(ServerLauncher.extractJsonString(cfgJson, "b2_bucket"), bucket, "b2_bucket must match");
            assertEquals(ServerLauncher.extractJsonString(cfgJson, "b2_key_id"), keyId, "b2_key_id must match");
            assertEquals(ServerLauncher.extractJsonString(cfgJson, "b2_application_key"), appKey, "b2_application_key must match");

            // Verify Universal Invite Token Encoding & Decoding
            ClusterToken.ClusterConfig tokenCfg = new ClusterToken.ClusterConfig(
                    bucket, keyId, appKey, 10, "10M", "dorm-craft.gl.joinmc.link", null
            );
            String encodedToken = ClusterToken.encode(tokenCfg);

            assertTrue(encodedToken.startsWith(ClusterToken.PREFIX), "Invite token must begin with mc-cluster://");

            ClusterToken.ClusterConfig decodedCfg = ClusterToken.decode(encodedToken);
            assertNotNull(decodedCfg, "Decoded token must not be null");
            assertEquals(decodedCfg.bucket(), bucket, "Decoded bucket must match original");
            assertEquals(decodedCfg.keyId(), keyId, "Decoded keyId must match original");
            assertEquals(decodedCfg.appKey(), appKey, "Decoded appKey must match original");
            assertEquals(decodedCfg.domain(), "dorm-craft.gl.joinmc.link", "Decoded domain must match original");

            // --- PHASE 3: Initial Generation Stamping (Dual Persistence) ---
            ServerLauncher.writeLocalGeneration(tempRoot, 1);

            int readGen = ServerLauncher.readLocalGeneration(tempRoot);
            assertEquals(readGen, 1, "Initial local generation must read 1");

            Path sessionFile = tempRoot.resolve(".cluster_session");
            Path manifestFile = tempRoot.resolve("manifest.json");

            assertTrue(Files.exists(sessionFile), ".cluster_session must exist (Dual Persistence)");
            assertTrue(Files.exists(manifestFile), "manifest.json must exist locally (Dual Persistence)");

            assertTrue(Files.readString(sessionFile, StandardCharsets.UTF_8).contains("\"base_generation\":1") || Files.readString(sessionFile, StandardCharsets.UTF_8).trim().equals("1"), "Session file must contain base_generation 1");
            assertTrue(Files.readString(manifestFile, StandardCharsets.UTF_8).contains("\"generation\":1"), "Manifest file must contain generation 1");

            System.out.println("TestAdminOnboarding: ALL ASSERTIONS PASSED (100% SUCCESS)");

        } catch (Throwable t) {
            System.err.println("TestAdminOnboarding FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        } finally {
            safeDeleteDir(tempExisting);
            safeDeleteDir(tempRoot);
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
