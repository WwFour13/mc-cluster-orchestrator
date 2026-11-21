import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class MockRcloneHarness {

    private static final String TMP = System.getProperty("java.io.tmpdir");
    public static final Path LOG_FILE = Paths.get(TMP, "mc_mock_rclone.log");
    public static final Path RULES_FILE = Paths.get(TMP, "mc_mock_rules.properties");
    public static final Path BAT_FILE = Paths.get(TMP, "mc_mock_rclone.bat");
    public static final Path INPUT_LOG = Paths.get(TMP, "mc_mock_last_input.txt");
    public static final Path LOCK_FILE = Paths.get(TMP, "mc_mock_lock.json");
    public static final Path MANIFEST_FILE = Paths.get(TMP, "mc_mock_manifest.json");
    // Server-issued version token (simple counter written per successful rcat cluster.lock write)
    public static final Path LOCK_VERSION_FILE = Paths.get(TMP, "mc_mock_lock_version.txt");
    // Interleaved-successor hook: if present, its contents replace the lock file on the NEXT rcat write
    public static final Path LOCK_REPLACE_ON_WRITE_FILE = Paths.get(TMP, "mc_mock_lock_replace.json");

    public static void setup() throws IOException {
        reset();
        Path baseDir = Paths.get(".").toAbsolutePath().normalize();
        String absCp = baseDir.resolve("tests").resolve("bin").toString()
                + ";" + baseDir.resolve("src").toString()
                + ";" + baseDir.resolve("tests").toString();

        String batContent = "@echo off\r\n"
                + "java -cp \"" + absCp + "\" MockRcloneHarness %*\r\n"
                + "exit /b %ERRORLEVEL%\r\n";
        Files.writeString(BAT_FILE, batContent, StandardCharsets.UTF_8);
    }

    public static void reset() throws IOException {
        if (Files.exists(LOG_FILE)) Files.delete(LOG_FILE);
        if (Files.exists(RULES_FILE)) Files.delete(RULES_FILE);
        if (Files.exists(INPUT_LOG)) Files.delete(INPUT_LOG);
        if (Files.exists(LOCK_FILE)) Files.delete(LOCK_FILE);
        if (Files.exists(MANIFEST_FILE)) Files.delete(MANIFEST_FILE);
        if (Files.exists(LOCK_VERSION_FILE)) Files.delete(LOCK_VERSION_FILE);
        if (Files.exists(LOCK_REPLACE_ON_WRITE_FILE)) Files.delete(LOCK_REPLACE_ON_WRITE_FILE);
    }

    public static void teardown() {
        try {
            if (Files.exists(LOG_FILE)) Files.delete(LOG_FILE);
            if (Files.exists(RULES_FILE)) Files.delete(RULES_FILE);
            if (Files.exists(INPUT_LOG)) Files.delete(INPUT_LOG);
            if (Files.exists(LOCK_FILE)) Files.delete(LOCK_FILE);
            if (Files.exists(MANIFEST_FILE)) Files.delete(MANIFEST_FILE);
            if (Files.exists(LOCK_VERSION_FILE)) Files.delete(LOCK_VERSION_FILE);
            if (Files.exists(LOCK_REPLACE_ON_WRITE_FILE)) Files.delete(LOCK_REPLACE_ON_WRITE_FILE);
            if (Files.exists(BAT_FILE)) Files.delete(BAT_FILE);
        } catch (Exception ignored) {}
    }

    public static String getMockRclonePath() {
        return BAT_FILE.toAbsolutePath().toString();
    }

    public static void setLockJson(String json) throws IOException {
        Files.writeString(LOCK_FILE, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void setManifestJson(String json) throws IOException {
        Files.writeString(MANIFEST_FILE, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void setRule(String key, String value) throws IOException {
        Properties p = new Properties();
        if (Files.exists(RULES_FILE)) {
            try (InputStream in = Files.newInputStream(RULES_FILE)) {
                p.load(in);
            }
        }
        p.setProperty(key, value);
        try (OutputStream out = Files.newOutputStream(RULES_FILE)) {
            p.store(out, null);
        }
    }

    public static List<String> getRecordedInvocations() throws IOException {
        if (!Files.exists(LOG_FILE)) return Collections.emptyList();
        return Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8);
    }

    public static String getLastInput() throws IOException {
        if (!Files.exists(INPUT_LOG)) return "";
        return Files.readString(INPUT_LOG, StandardCharsets.UTF_8);
    }

    /**
     * Returns the server-issued version token for the last successful rcat to cluster.lock.
     * This is an independent counter assigned by the mock (not a hash of the content), simulating
     * the ETag or VersionId that a real object store would assign per write.
     */
    public static String getLastLockVersion() throws IOException {
        if (!Files.exists(LOCK_VERSION_FILE)) return "";
        return Files.readString(LOCK_VERSION_FILE, StandardCharsets.UTF_8).trim();
    }

    /**
     * Arms the interleaved-successor hook: on the NEXT rcat to cluster.lock, the mock
     * will write {@code json} to the lock file instead of stdin, simulating a concurrent
     * acquisition that wins the race between the releasing host's read and write.
     */
    public static void setLockReplaceOnNextWrite(String json) throws IOException {
        Files.writeString(LOCK_REPLACE_ON_WRITE_FILE, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // CLI Entrypoint invoked by mock_rclone.bat
    public static void main(String[] args) {
        try {
            String invocation = String.join(" ", args);
            // Append invocation to log
            Files.writeString(LOG_FILE, invocation + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Read stdin if available (for rcat)
            byte[] stdinBytes = new byte[0];
            if (args.length > 0 && "rcat".equals(args[0])) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = System.in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                stdinBytes = baos.toByteArray();
                Files.write(INPUT_LOG, stdinBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                String target = args.length > 1 ? args[1] : "";
                if (target.contains("cluster.lock")) {
                    // Interleaved-successor hook: if the hook file exists, write its content instead of stdin.
                    // This simulates Host B acquiring the lock between Host A's read and Host A's tombstone write.
                    if (Files.exists(LOCK_REPLACE_ON_WRITE_FILE)) {
                        byte[] hookContent = Files.readAllBytes(LOCK_REPLACE_ON_WRITE_FILE);
                        Files.delete(LOCK_REPLACE_ON_WRITE_FILE);
                        Files.write(LOCK_FILE, hookContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } else {
                        Files.write(LOCK_FILE, stdinBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    // Assign a server-issued version token (simple monotonically-increasing counter).
                    // This is independent of the content — analogous to an ETag or VersionId from a real store.
                    int version = 1;
                    if (Files.exists(LOCK_VERSION_FILE)) {
                        try { version = Integer.parseInt(Files.readString(LOCK_VERSION_FILE).trim()) + 1; } catch (Exception ignored) {}
                    }
                    Files.writeString(LOCK_VERSION_FILE, String.valueOf(version), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else if (target.contains("manifest.json")) {
                    Files.write(MANIFEST_FILE, stdinBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

            Properties rules = new Properties();
            if (Files.exists(RULES_FILE)) {
                try (InputStream in = Files.newInputStream(RULES_FILE)) {
                    rules.load(in);
                }
            }

            // Command-specific responses
            if (args.length > 0) {
                String cmd = args[0];

                if ("rcat".equals(cmd)) {
                    String target = args.length > 1 ? args[1] : "";
                    if (rules.containsKey("lockWriteExitCode")) {
                        int lCode = Integer.parseInt(rules.getProperty("lockWriteExitCode"));
                        System.err.println("Mock rclone: simulated cluster.lock write failure (exit " + lCode + ")");
                        System.exit(lCode);
                    }
                    if (target.contains("manifest.json") && rules.containsKey("manifestWriteExitCode")) {
                        int mCode = Integer.parseInt(rules.getProperty("manifestWriteExitCode"));
                        System.err.println("Mock rclone: simulated manifest.json write failure (exit " + mCode + ")");
                        System.exit(mCode);
                    }
                } else if ("lsf".equals(cmd)) {
                    String fullInvocation = String.join(" ", args);
                    if (fullInvocation.contains("cluster.lock")) {
                        if (rules.containsKey("lockReadExitCode")) {
                            int lrCode = Integer.parseInt(rules.getProperty("lockReadExitCode"));
                            System.err.println("Mock rclone: simulated cluster.lock read failure (lsf exit " + lrCode + ")");
                            System.exit(lrCode);
                        }
                        if (Files.exists(LOCK_FILE)) {
                            System.out.println("cluster.lock");
                        }
                    }
                } else if ("cat".equals(cmd)) {
                    String target = args.length > 1 ? args[1] : "";
                    if (target.contains("manifest.json")) {
                        if (Files.exists(MANIFEST_FILE)) {
                            System.out.println(Files.readString(MANIFEST_FILE, StandardCharsets.UTF_8).trim());
                        } else {
                            System.out.println("{\"generation\":26,\"last_host\":\"ClusterNode-Alpha\",\"min_orchestrator_version\":\"2.1.10\"}");
                        }
                    } else if (target.contains("cluster.lock")) {
                        if (rules.containsKey("lockReadExitCode")) {
                            int lrCode = Integer.parseInt(rules.getProperty("lockReadExitCode"));
                            System.err.println("Mock rclone: simulated cluster.lock read failure (cat exit " + lrCode + ")");
                            System.exit(lrCode);
                        }
                        if (Files.exists(LOCK_FILE)) {
                            System.out.println(Files.readString(LOCK_FILE, StandardCharsets.UTF_8).trim());
                        }
                    } else if (target.contains("remote_stop.signal")) {
                        String stopSignal = rules.getProperty("stopSignal", "");
                        if (!stopSignal.isEmpty()) {
                            System.out.println(stopSignal);
                        } else {
                            System.exit(1);
                        }
                    }
                }
            }

            // Check if failure is requested
            String exitCodeStr = rules.getProperty("exitCode", "0");
            int exitCode = Integer.parseInt(exitCodeStr);
            System.exit(exitCode);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
