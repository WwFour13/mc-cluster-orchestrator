import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Unit Test: TestStarterPackLifecycle
 *
 * Verifies the complete offline export and import lifecycle of starter packs:
 * 1. Exports server directory to a zip archive.
 * 2. Asserts security scrubbing: sensitive credentials (config.json) are strictly EXCLUDED.
 * 3. Asserts essential game assets (world, plugins, core.dat, playit.toml, manifest) are INCLUDED.
 * 4. Imports the package into a clean target directory and validates byte-level fidelity,
 *    EULA acceptance, and generation session alignment.
 *
 * Runs 100% locally in ephemeral temporary sandboxes without touching production assets.
 */
public class TestStarterPackLifecycle {

    public static void main(String[] args) {
        System.out.println("Running TestStarterPackLifecycle...");
        Path tempSrc = null;
        Path tempZip = null;
        Path tempDest = null;

        try {
            tempSrc = Files.createTempDirectory("test-starterpack-src");
            tempZip = Files.createTempFile("test-starterpack-export", ".zip");
            tempDest = Files.createTempDirectory("test-starterpack-dest");

            // 1. Populate source directory with mock server files
            Path worldDir = tempSrc.resolve("world");
            Path regionDir = worldDir.resolve("region");
            Files.createDirectories(regionDir);
            Files.writeString(worldDir.resolve("level.dat"), "MOCK_LEVEL_DAT_CONTENT", StandardCharsets.UTF_8);
            Files.writeString(regionDir.resolve("r.0.0.mca"), "MOCK_CHUNK_DATA", StandardCharsets.UTF_8);

            Path binDir = tempSrc.resolve("bin");
            Files.createDirectories(binDir);
            Files.writeString(binDir.resolve("core.dat"), "MOCK_PAPER_CORE_BINARY", StandardCharsets.UTF_8);
            Files.writeString(binDir.resolve("rclone.exe"), "MOCK_RCLONE_EXE", StandardCharsets.UTF_8);
            Files.writeString(binDir.resolve("icon.ico"), "MOCK_ICON", StandardCharsets.UTF_8);

            Path pluginsDir = tempSrc.resolve("plugins");
            Files.createDirectories(pluginsDir);
            Files.writeString(pluginsDir.resolve("ClusterGuard.jar"), "MOCK_CLUSTER_GUARD_JAR", StandardCharsets.UTF_8);

            Files.writeString(tempSrc.resolve("server.properties"), "motd=Cluster Test Server\nserver-port=25565\n", StandardCharsets.UTF_8);
            Files.writeString(tempSrc.resolve("playit.toml"), "secret_key = \"test_secret_12345\"\n", StandardCharsets.UTF_8);
            Files.writeString(tempSrc.resolve("eula.txt"), "eula=false\n", StandardCharsets.UTF_8);

            // Manifest & Session at Generation #28
            Files.writeString(tempSrc.resolve(".cluster_session"), "28", StandardCharsets.UTF_8);
            Files.writeString(tempSrc.resolve("manifest.json"),
                    "{\"generation\":28,\"last_host\":\"Aventador\",\"timestamp\":\"2026-11-18T21:24:00Z\",\"orchestrator_version\":\"2.1.11\"}",
                    StandardCharsets.UTF_8);

            // Sensitive cloud configuration that MUST NOT be exported
            Files.writeString(tempSrc.resolve("config.json"),
                    "{\"b2_bucket\":\"secret-bucket\",\"b2_key_id\":\"K123\",\"b2_application_key\":\"SUPER_SECRET_KEY\"}",
                    StandardCharsets.UTF_8);

            // 2. Execute exportStarterPack
            ServerLauncher.exportStarterPack(tempSrc, tempZip, msg -> {});
            assertTrue(Files.exists(tempZip), "Exported zip archive must exist");
            assertTrue(Files.size(tempZip) > 0, "Exported zip must not be empty");

            // 3. Inspect Zip Archive Entries
            Set<String> zipEntries = new HashSet<>();
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    zipEntries.add(entry.getName());
                    zis.closeEntry();
                }
            }

            // Security assertion: config.json must NOT be in the zip
            assertFalse(zipEntries.contains("config.json"), "SECURITY LEAK: config.json must NEVER be included in starter pack!");

            // Essential inclusions
            assertTrue(zipEntries.contains("world/level.dat"), "world/level.dat must be included");
            assertTrue(zipEntries.contains("world/region/r.0.0.mca"), "world region files must be included");
            assertTrue(zipEntries.contains("bin/core.dat"), "bin/core.dat must be included");
            assertTrue(zipEntries.contains("bin/rclone.exe"), "bin/rclone.exe must be included");
            assertTrue(zipEntries.contains("plugins/ClusterGuard.jar"), "plugins/ClusterGuard.jar must be included");
            assertTrue(zipEntries.contains("server.properties"), "server.properties must be included");
            assertTrue(zipEntries.contains("playit.toml"), "playit.toml must be included");
            assertTrue(zipEntries.contains("manifest.json"), "manifest.json must be included");
            assertTrue(zipEntries.contains(".cluster_session"), ".cluster_session must be included");
            assertTrue(zipEntries.contains("eula.txt"), "eula.txt must be included");

            // 4. Execute importStarterPack into clean destination
            String clusterId = ServerLauncher.importStarterPack(tempZip, tempDest, null, msg -> {});
            assertNotNull(clusterId, "Import must return a valid cluster ID");

            // Verify unpacked destination files
            assertTrue(Files.exists(tempDest.resolve("world/level.dat")), "Imported world/level.dat must exist");
            assertEquals(Files.readString(tempDest.resolve("world/level.dat"), StandardCharsets.UTF_8),
                    "MOCK_LEVEL_DAT_CONTENT", "world/level.dat content must match");

            assertTrue(Files.exists(tempDest.resolve("world/region/r.0.0.mca")), "Imported region file must exist");
            assertTrue(Files.exists(tempDest.resolve("bin/core.dat")), "Imported bin/core.dat must exist");
            assertTrue(Files.exists(tempDest.resolve("plugins/ClusterGuard.jar")), "Imported ClusterGuard must exist");
            assertTrue(Files.exists(tempDest.resolve("playit.toml")), "Imported playit.toml must exist");

            // EULA auto-acceptance assertion
            Path importedEula = tempDest.resolve("eula.txt");
            assertTrue(Files.exists(importedEula), "eula.txt must exist");
            assertTrue(Files.readString(importedEula).contains("eula=true"), "eula.txt must be automatically set to true");

            // Generation alignment assertion
            int importedGen = ServerLauncher.readLocalGeneration(tempDest);
            assertEquals(importedGen, 28, "Imported cluster session must be aligned to Generation #28 from manifest");

            System.out.println("TestStarterPackLifecycle: ALL ASSERTIONS PASSED (100% SUCCESS)");

        } catch (Throwable t) {
            System.err.println("TestStarterPackLifecycle FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        } finally {
            // Clean up temporary sandboxes
            safeDeleteDir(tempSrc);
            safeDeleteDir(tempDest);
            if (tempZip != null) {
                try { Files.deleteIfExists(tempZip); } catch (Exception ignored) {}
            }
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion Failed: " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError("Assertion Failed: " + message);
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
