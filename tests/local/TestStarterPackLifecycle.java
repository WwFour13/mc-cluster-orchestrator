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
        Path updateZip = null;

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

            // In-place update assertion: running Windows launcher executables must never be overwritten
            Files.writeString(tempDest.resolve("MinecraftCluster.exe"), "RUNNING_EXE", StandardCharsets.UTF_8);
            Files.writeString(tempDest.resolve("ServerLauncher.jar"), "RUNNING_JAR", StandardCharsets.UTF_8);
            updateZip = Files.createTempFile("test-starterpack-inplace", ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(updateZip,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                ZipEntry exeEntry = new ZipEntry("MinecraftCluster.exe");
                zos.putNextEntry(exeEntry);
                zos.write("PACKAGED_EXE".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry jarEntry = new ZipEntry("ServerLauncher.jar");
                zos.putNextEntry(jarEntry);
                zos.write("PACKAGED_JAR".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry manifestEntry = new ZipEntry("manifest.json");
                zos.putNextEntry(manifestEntry);
                zos.write("{\"generation\":29}".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            int updatedGen = ServerLauncher.updateClusterInPlace(updateZip, tempDest, msg -> {});
            assertEquals(updatedGen, 29, "In-place update must apply the sync pack generation");
            assertEquals(Files.readString(tempDest.resolve("MinecraftCluster.exe"), StandardCharsets.UTF_8),
                    "RUNNING_EXE", "Running MinecraftCluster.exe must not be overwritten in-place");
            assertEquals(Files.readString(tempDest.resolve("ServerLauncher.jar"), StandardCharsets.UTF_8),
                    "RUNNING_JAR", "Running ServerLauncher.jar must not be overwritten in-place");
            assertTrue(Files.exists(tempDest.resolve("ServerLauncher.jar.update")),
                    "Launcher JAR update must be staged for next restart");
            assertEquals(Files.readString(tempDest.resolve("ServerLauncher.jar.update"), StandardCharsets.UTF_8),
                    "PACKAGED_JAR", "Staged JAR must contain the packaged launcher");
            Files.deleteIfExists(updateZip);

            // Nested package-root regression: arbitrary wrapper folders must be flattened.
            Path nestedZip = Files.createTempFile("test-starterpack-nested", ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(nestedZip,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                ZipEntry worldEntry = new ZipEntry("download/cohost-pack/world/level.dat");
                zos.putNextEntry(worldEntry);
                zos.write("NESTED_LEVEL_DAT".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry pluginEntry = new ZipEntry("download/cohost-pack/plugins/Example.jar");
                zos.putNextEntry(pluginEntry);
                zos.write("NESTED_PLUGIN".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry manifestEntry = new ZipEntry("download/cohost-pack/manifest.json");
                zos.putNextEntry(manifestEntry);
                zos.write("{\"generation\":30}".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry propertiesEntry = new ZipEntry("download/cohost-pack/server.properties");
                zos.putNextEntry(propertiesEntry);
                zos.write("motd=Nested Pack".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            Path nestedDest = Files.createTempDirectory("test-starterpack-nested-dest");
            try {
                ServerLauncher.importStarterPack(nestedZip, nestedDest, null, msg -> {});
                assertTrue(Files.exists(nestedDest.resolve("world/level.dat")),
                        "Nested package root must flatten world/ to the target root");
                assertTrue(Files.exists(nestedDest.resolve("plugins/Example.jar")),
                        "Nested package root must flatten plugins/ to the target root");
                assertTrue(Files.exists(nestedDest.resolve("manifest.json")),
                        "Nested package root must flatten manifest.json to the target root");
                assertTrue(Files.exists(nestedDest.resolve("server.properties")),
                        "Nested package root must flatten server.properties to the target root");
                assertEquals(Files.readString(nestedDest.resolve("world/level.dat"), StandardCharsets.UTF_8),
                        "NESTED_LEVEL_DAT", "Nested world contents must remain intact");

                Files.writeString(nestedDest.resolve("world/level.dat"), "OLD_LEVEL_DAT", StandardCharsets.UTF_8);
                int nestedUpdateGen = ServerLauncher.updateClusterInPlace(nestedZip, nestedDest, msg -> {});
                assertEquals(nestedUpdateGen, 30, "In-place nested update must read the nested manifest generation");
                assertEquals(Files.readString(nestedDest.resolve("world/level.dat"), StandardCharsets.UTF_8),
                        "NESTED_LEVEL_DAT", "In-place nested update must flatten and overwrite world data");
            } finally {
                Files.deleteIfExists(nestedZip);
                safeDeleteDir(nestedDest);
            }

            Path emptyZip = Files.createTempFile("test-starterpack-empty", ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(emptyZip,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                zos.putNextEntry(new ZipEntry("just-a-folder/"));
                zos.closeEntry();
            }
            boolean emptyRejected = false;
            try {
                ServerLauncher.importStarterPack(emptyZip,
                        Files.createTempDirectory("test-starterpack-empty-dest"), null, msg -> {});
            } catch (IOException ex) {
                emptyRejected = ex.getMessage() != null && ex.getMessage().contains("contains no files");
            } finally {
                Files.deleteIfExists(emptyZip);
            }
            assertTrue(emptyRejected, "ZIP archives containing only an empty folder must be rejected");

            // Windows-only proof: a running executable is locked, reproducing the legacy direct-write failure.
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                String windir = System.getenv("WINDIR");
                Path systemExe = windir != null
                        ? Paths.get(windir, "System32", "cmd.exe")
                        : Paths.get("C:\\Windows\\System32\\cmd.exe");
                if (Files.exists(systemExe)) {
                    Process runningExe = null;
                    try {
                        Path runningExePath = tempDest.resolve("LegacyRunningLauncher.exe");
                        Files.copy(systemExe, runningExePath,
                                StandardCopyOption.REPLACE_EXISTING);
                        runningExe = new ProcessBuilder(runningExePath.toString(), "/c",
                                "ping", "127.0.0.1", "-n", "5", "-w", "1000").start();
                        Thread.sleep(500);

                        boolean legacyWriteFailed = false;
                        try {
                            Files.write(runningExePath, "LEGACY_REPLACEMENT".getBytes(StandardCharsets.UTF_8),
                                    StandardOpenOption.TRUNCATE_EXISTING);
                        } catch (IOException ex) {
                            legacyWriteFailed = true;
                        }

                        assertTrue(legacyWriteFailed,
                                "Legacy direct-write reproduction: running Windows executables must reject replacement");
                        System.out.println("  ✓ PASS: Legacy direct write hit the running executable file lock");
                    } finally {
                        if (runningExe != null) {
                            runningExe.destroyForcibly();
                        }
                    }
                } else {
                    System.out.println("  ↷ SKIP: Windows system executable not available for legacy lock reproduction");
                }
            } else {
                System.out.println("  ↷ SKIP: Running-executable lock reproduction requires Windows");
            }

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
            if (updateZip != null) {
                try { Files.deleteIfExists(updateZip); } catch (Exception ignored) {}
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
