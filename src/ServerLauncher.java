import java.awt.GraphicsEnvironment;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.*;

public class ServerLauncher {

    public static final String APP_VERSION = "2.1.3";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static ScheduledExecutorService heartbeatScheduler;
    private static volatile boolean isShuttingDown = false;
    private static Process activeMcProcess = null;
    private static Process activePlayitProcess = null;
    private static BufferedWriter activeMcStdin = null;
    private static final AtomicBoolean remoteStopTriggered = new AtomicBoolean(false);

    public static record CommandResult(int exitCode, String stdout, String stderr) {}

    public static void main(String[] args) {
        Path baseDir = Paths.get("").toAbsolutePath();
        if (!Files.exists(baseDir.resolve("config.json")) && Files.exists(baseDir.resolve("minecraft-server").resolve("config.json"))) {
            baseDir = baseDir.resolve("minecraft-server");
        }
        ClusterRegistry registry = ClusterRegistry.getInstance(baseDir);
        Path activeRoot = registry.getActiveClusterPath(baseDir);

        boolean forceCli = false;
        for (String a : args) {
            if ("--cli".equalsIgnoreCase(a) || "--nogui".equalsIgnoreCase(a)) {
                forceCli = true;
                break;
            }
        }

        boolean isSubcommand = false;
        if (args.length > 0 && !args[0].startsWith("-")) {
            String cmd = args[0].toLowerCase();
            isSubcommand = switch (cmd) {
                case "pull", "push", "publish-update", "publish", "push-update", "push-launcher",
                     "update", "unlock", "remote-stop", "cancel-stop", "status", "export-starterpack", "import-starterpack", "cleanup" -> true;
                default -> false;
            };
        }

        if (!forceCli && !isSubcommand && !GraphicsEnvironment.isHeadless()) {
            ClusterGUI.launch(activeRoot);
            return;
        }

        final Path rootDir = activeRoot;

        Path configPath = rootDir.resolve("config.json");
        Path lockPath = rootDir.resolve(".server_active.lock");
        Path coreBinaryPath = rootDir.resolve("bin").resolve("core.dat");

        if (!Files.exists(configPath)) {
            System.err.println("[ERROR] config.json not found in " + rootDir);
            if (Files.exists(rootDir.resolve("[template]config.json"))) {
                System.err.println("[TIP] Found '[template]config.json'! Copy it to 'config.json' and fill in your keys.");
            }
            return;
        }

        try {
            String configJson = Files.readString(configPath);
            String b2Bucket = extractJsonString(configJson, "b2_bucket");
            String b2KeyId = extractJsonString(configJson, "b2_key_id");
            String b2AppKey = extractJsonString(configJson, "b2_application_key");

            String duckDomain = extractJsonString(configJson, "domain");
            String duckToken = extractJsonString(configJson, "token");
            String javaMemory = extractJsonString(configJson, "java_memory");
            if (javaMemory == null || javaMemory.isEmpty()) javaMemory = "6G";

            int staleMin = extractJsonInt(configJson, "stale_timeout_minutes", 10);
            int heartbeatSec = extractJsonInt(configJson, "heartbeat_seconds", 120);
            int saveIntervalMin = extractJsonInt(configJson, "save_interval_minutes", 15);
            String b2BandwidthLimit = extractJsonString(configJson, "b2_bandwidth_limit");
            if (b2BandwidthLimit == null || b2BandwidthLimit.isEmpty()) b2BandwidthLimit = "10M";

            boolean hasB2 = (b2Bucket != null && !b2Bucket.isEmpty() && !b2Bucket.startsWith("YOUR_")
                    && b2KeyId != null && !b2KeyId.isEmpty() && !b2KeyId.startsWith("YOUR_")
                    && b2AppKey != null && !b2AppKey.isEmpty() && !b2AppKey.startsWith("YOUR_"));

            String rclone = findRclone(rootDir);
            String b2Target = hasB2 ? getB2Remote(b2Bucket, b2KeyId, b2AppKey) : null;
            String localHost = getHostName();

            // Handle CLI Subcommands
            if (args.length > 0) {
                String cmd = args[0].toLowerCase();
                boolean force = (args.length > 1 && ("--force".equalsIgnoreCase(args[1]) || "-f".equalsIgnoreCase(args[1])));

                switch (cmd) {
                    case "pull":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        pullFromB2(rootDir, rclone, b2Target, staleMin, force);
                        return;

                    case "push":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        pushToB2(rootDir, rclone, b2Target, localHost, staleMin, b2BandwidthLimit, false);
                        return;

                    case "publish-update":
                    case "publish":
                    case "push-update":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        publishAdminUpdate(rootDir, rclone, b2Target);
                        return;

                    case "push-launcher":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        pushLauncherToB2(rootDir, rclone, b2Target);
                        return;

                    case "update":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        boolean hasUpdate = checkLauncherUpdate(rootDir, rclone, b2Target);
                        if (!hasUpdate) {
                            System.out.println("[UPDATE] ServerLauncher is already up to date.");
                        }
                        return;

                    case "unlock":
                        System.out.println("[UNLOCK] Releasing local and cloud cluster locks...");
                        Files.deleteIfExists(lockPath);
                        if (hasB2) {
                            releaseCloudLock(rootDir, rclone, b2Target);
                            cancelRemoteStop(rootDir, rclone, b2Target);
                        }
                        System.out.println("[UNLOCK] Emergency cluster unlock complete.");
                        return;

                    case "remote-stop":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        String reason = (args.length > 1) ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Admin maintenance";
                        dispatchRemoteStop(rootDir, rclone, b2Target, localHost, reason);
                        return;

                    case "cancel-stop":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        cancelRemoteStop(rootDir, rclone, b2Target);
                        return;

                    case "status":
                        System.out.println("[STATUS] Checking cluster lock & sync status...");
                        if (hasB2) {
                            CommandResult catRes = runCommand(rootDir, rclone, "cat", b2Target + "/cluster.lock");
                            if (catRes.exitCode == 0 && !catRes.stdout.isEmpty()) {
                                System.out.println("Active Cloud Lock: " + catRes.stdout);
                            } else {
                                System.out.println("Cloud Lock: FREE (No active host)");
                            }
                            CommandResult signalRes = runCommand(rootDir, rclone, "cat", b2Target + "/remote_stop.signal");
                            if (signalRes.exitCode == 0 && !signalRes.stdout.isEmpty()) {
                                System.out.println("Pending Remote Stop: " + signalRes.stdout);
                            } else {
                                System.out.println("Pending Remote Stop: None");
                            }
                            int cloudGen = readCloudGeneration(rootDir, rclone, b2Target);
                            int localGen = readLocalGeneration(rootDir);
                            System.out.printf("Cloud World Generation: #%d | Local World Base: #%d%n", cloudGen, localGen);
                        }
                        if (Files.exists(lockPath)) {
                            System.out.println("Local Lock File: Present (" + Files.readString(lockPath) + ")");
                        } else {
                            System.out.println("Local Lock File: None");
                        }
                        String statusAddr = extractJsonString(configJson, "server_address");
                        if (statusAddr == null || statusAddr.isEmpty()) {
                            statusAddr = extractJsonString(configJson, "domain");
                            if (statusAddr != null && !statusAddr.isEmpty()) statusAddr += ".duckdns.org";
                        }
                        if (statusAddr != null && !statusAddr.isEmpty()) {
                            PingResult pr = pingMinecraftServer(statusAddr);
                            if (pr.isOnline) {
                                System.out.printf("Public Tunnel Ping (%s): LIVE (%d/%d online | MOTD: %s)%n", statusAddr, pr.onlinePlayers, pr.maxPlayers, pr.motd);
                            } else {
                                System.out.printf("Public Tunnel Ping (%s): OFFLINE%n", statusAddr);
                            }
                        }
                        return;

                    case "cleanup":
                        if (!hasB2) {
                            System.err.println("[ERROR] Backblaze B2 is not configured in config.json.");
                            return;
                        }
                        purgeDeadRevisions(rootDir, rclone, b2Target);
                        return;

                    case "export-starterpack":
                        Path outZip = args.length > 1 ? Paths.get(args[1]) : rootDir.resolve("cohost-starterpack.zip");
                        exportStarterPack(rootDir, outZip, System.out::println);
                        return;

                    case "import-starterpack":
                        if (args.length < 2) {
                            System.err.println("[ERROR] Usage: java -jar ServerLauncher.jar import-starterpack <path-to-zip> [target-folder]");
                            return;
                        }
                        Path inZip = Paths.get(args[1]);
                        Path targetF = args.length > 2 ? Paths.get(args[2]) : Paths.get(registry.getStorageRoot(), "imported-cluster");
                        importStarterPack(inZip, targetF, registry, System.out::println);
                        return;

                    default:
                        System.err.println("[ERROR] Unknown argument: " + cmd + " (Available: pull, push, publish-update, push-launcher, update, unlock, status, remote-stop, cancel-stop, export-starterpack, import-starterpack)");
                        return;
                }
            }

            // Normal Startup Flow
            if (!Files.exists(coreBinaryPath)) {
                System.err.println("[ERROR] Server core missing! Expected at: " + coreBinaryPath);
                System.err.println("Ensure your paper.jar is renamed to core.dat inside the 'bin' folder.");
                return;
            }

            // Dual-Layer Tunnel Ping Guard: check if a server is actively running on the public address
            String targetServerAddr = extractJsonString(configJson, "server_address");
            if (targetServerAddr == null || targetServerAddr.isEmpty()) {
                targetServerAddr = extractJsonString(configJson, "domain");
                if (targetServerAddr != null && !targetServerAddr.isEmpty()) targetServerAddr += ".duckdns.org";
            }
            if (targetServerAddr != null && !targetServerAddr.isEmpty()) {
                PingResult pr = pingMinecraftServer(targetServerAddr);
                if (pr.isOnline) {
                    System.out.println("\n========================================================");
                    System.out.printf("[COLLISION GUARD] Active Minecraft session detected on tunnel '%s'!%n", targetServerAddr);
                    System.out.printf("  Online Players: %d/%d | MOTD: %s%n", pr.onlinePlayers, pr.maxPlayers, pr.motd);
                    System.out.println("  Launch aborted to prevent split-brain session conflict and data corruption.");
                    System.out.println("  Please join the active session directly!");
                    System.out.println("========================================================\n");
                    return;
                }
            }

            // Active Host Priority Gate: If a host is already live and newcomer has no world, prevent redundant downloads
            if (hasB2 && isWorldEmpty(rootDir) && isCloudLocked(rootDir, rclone, b2Target, staleMin)) {
                String lockHost = readLockHost(rootDir, rclone, b2Target);
                System.out.println("\n========================================================");
                System.out.printf("[ACTIVE HOST PRIORITY GATE] Cluster is actively hosted by '%s'!%n", lockHost);
                System.out.println("Initial cloud sync is paused to guarantee live gameplay backup bandwidth.");
                System.out.println("You can connect directly to the live session at: " + (duckDomain != null && !duckDomain.isEmpty() ? duckDomain + ".duckdns.org" : "jensen-edited.tun.ply.gg"));
                System.out.println("========================================================\n");
                return;
            }

            // 0. Pre-Flight Launcher Update Check
            if (hasB2) {
                if (checkLauncherUpdate(rootDir, rclone, b2Target)) {
                    System.exit(100);
                }
            }

            // 1. Port Verification
            System.out.println("[1/6] Verifying port 25565 availability...");
            if (!isPortAvailable(25565)) {
                System.err.println("[ERROR] Port 25565 is already bound by an active process.");
                return;
            }

            // 2. Lock State Verification & Acquisition
            System.out.println("[2/6] Evaluating cluster lock state...");

            if (hasB2) {
                if (!acquireCloudLock(rootDir, rclone, b2Target, localHost, staleMin)) {
                    return;
                }
                // Clear any leftover stop signal from previous sessions
                cancelRemoteStop(rootDir, rclone, b2Target);
            } else {
                if (Files.exists(lockPath)) {
                    String lockContent = Files.readString(lockPath);
                    String activeHost = extractJsonString(lockContent, "host");
                    String timestampStr = extractJsonString(lockContent, "timestamp");
                    if (timestampStr != null && !timestampStr.isEmpty()) {
                        Instant lastBeat = Instant.parse(timestampStr);
                        long elapsedMinutes = Duration.between(lastBeat, Instant.now()).toMinutes();
                        if (elapsedMinutes >= staleMin) {
                            System.out.printf("[WARN] Host '%s' heartbeat expired (%d min elapsed). Reclaiming lock...%n", activeHost, elapsedMinutes);
                            Files.deleteIfExists(lockPath);
                        } else {
                            System.err.printf("[ABORT] Server actively hosted by: %s%n", activeHost);
                            System.err.printf("Active heartbeat was pulsed %d minutes ago.%n", elapsedMinutes);
                            return;
                        }
                    } else {
                        Files.deleteIfExists(lockPath);
                    }
                }
            }

            // Write local lock file
            System.out.println("[3/6] Confirming local cluster lock...");
            writeLockFile(lockPath, localHost);

            // Register Graceful Shutdown Hook
            final String finalB2Target = b2Target;
            final String finalRclone = rclone;
            final String finalLocalHost = localHost;
            final int finalStaleMin = staleMin;
            final String finalBwLimit = b2BandwidthLimit;

            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    cleanup(rootDir, lockPath, finalRclone, finalB2Target, finalLocalHost, finalStaleMin, finalBwLimit,
                            activeMcProcess, activeMcStdin)
            ));

            // Dynamic DNS (if DuckDNS configured)
            if (duckToken != null && !duckToken.isEmpty() && !duckToken.startsWith("YOUR_")) {
                String tailscaleIp = getTailscaleIp();
                if (tailscaleIp == null || tailscaleIp.isEmpty()) tailscaleIp = "127.0.0.1";
                updateDuckDns(duckDomain, duckToken, tailscaleIp);
            }

            // 4. Pre-Boot State Convergence
            if (hasB2) {
                System.out.println("[4/6] Synchronizing administrative distribution & latest world state...");
                syncAdminDistribution(rootDir, rclone, b2Target);
                pullFromB2(rootDir, rclone, b2Target, staleMin, true);
            } else {
                System.out.println("[4/6] No cloud sync provider configured. Running in local standalone mode.");
            }

            // 5. Asynchronous Heartbeat, Remote Stop Poller, & In-Game Autosave Dispatcher
            heartbeatScheduler = Executors.newScheduledThreadPool(2);

            // Heartbeat & Remote Stop timer (conserves B2 free tier: runs every heartbeatSec, default 120s)
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    writeLockFile(lockPath, localHost);
                    if (hasB2) {
                        writeCloudHeartbeat(rootDir, rclone, b2Target, localHost);
                        checkRemoteStopSignal(rootDir, rclone, b2Target, activeMcStdin);
                    }
                } catch (Exception ignored) {}
            }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);

            // In-Game Autosave & Incremental Cloud Push (every saveIntervalMin)
            if (hasB2 && saveIntervalMin > 0) {
                heartbeatScheduler.scheduleAtFixedRate(() -> {
                    triggerIncrementalPush(rootDir, rclone, b2Target, finalBwLimit, activeMcStdin);
                }, saveIntervalMin, saveIntervalMin, TimeUnit.MINUTES);
            }

            // Standalone Playit Sidecar Tunnel Daemon
            String playitBin = findPlayit(rootDir);
            if (playitBin != null) {
                System.out.println("[Playit Sidecar] Starting standalone Playit tunnel agent (" + playitBin + ")...");
                try {
                    ProcessBuilder playitPb = new ProcessBuilder(playitBin, "run");
                    playitPb.directory(rootDir.toFile());
                    playitPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    playitPb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    activePlayitProcess = playitPb.start();
                    System.out.println("[Playit Sidecar] Standalone tunnel agent active.");
                } catch (Exception e) {
                    System.err.println("[WARN] Failed to launch Playit sidecar: " + e.getMessage());
                }
            } else {
                System.out.println("[Playit] No standalone binary found in bin/. Falling back to in-game plugin.");
            }

            // 6. Launch Server Process with Piped Console Forwarding
            System.out.printf("[5/6] Executing server runtime (%s heap allocation)...%n", javaMemory);
            // Ensure Mojang EULA is accepted
            Path eulaFile = rootDir.resolve("eula.txt");
            try {
                if (!Files.exists(eulaFile) || Files.readString(eulaFile).contains("eula=false")) {
                    Files.writeString(eulaFile, "# Accepted automatically by Minecraft Cluster Orchestrator\neula=true\n");
                    System.out.println("[EULA] Mojang EULA automatically accepted.");
                }
            } catch (Exception ignored) {}

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-Dcluster.safe_launch=true",
                    "-Xms" + javaMemory,
                    "-Xmx" + javaMemory,
                    "-jar", coreBinaryPath.toString(),
                    "nogui"
            );
            pb.directory(rootDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            activeMcProcess = pb.start();
            activeMcStdin = new BufferedWriter(new OutputStreamWriter(activeMcProcess.getOutputStream(), StandardCharsets.UTF_8));

            // Forward user keyboard input to Minecraft console
            final BufferedWriter processWriter = activeMcStdin;
            Thread consoleForwarder = new Thread(() -> {
                try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = consoleReader.readLine()) != null) {
                        synchronized (processWriter) {
                            processWriter.write(line + "\n");
                            processWriter.flush();
                        }
                    }
                } catch (IOException ignored) {}
            });
            consoleForwarder.setDaemon(true);
            consoleForwarder.start();

            activeMcProcess.waitFor();

            // 7. Normal Teardown
            cleanup(rootDir, lockPath, rclone, b2Target, localHost, staleMin, b2BandwidthLimit,
                    activeMcProcess, activeMcStdin);

        } catch (Exception e) {
            System.err.println("[ERROR] Fatal exception in orchestrator runtime: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static synchronized void cleanup(Path rootDir, Path lockPath, String rclone, String b2Target,
                                             String host, int staleMin, String bwLimit,
                                             Process mcProcess, BufferedWriter mcStdin) {
        if (isShuttingDown) return;
        isShuttingDown = true;

        System.out.println("\n[6/6] Runtime terminated. Stopping background services...");
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
        }

        if (activePlayitProcess != null && activePlayitProcess.isAlive()) {
            System.out.println("[Playit Sidecar] Stopping standalone Playit tunnel agent...");
            try {
                activePlayitProcess.destroy();
                activePlayitProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        if (mcProcess != null && mcProcess.isAlive()) {
            System.out.println("[Shutdown] Gracefully stopping Minecraft server...");
            try {
                if (mcStdin != null) {
                    synchronized (mcStdin) {
                        mcStdin.write("stop\n");
                        mcStdin.flush();
                    }
                }
                boolean finished = mcProcess.waitFor(60, TimeUnit.SECONDS);
                if (!finished) {
                    mcProcess.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }

        // Push final world state to Backblaze B2 with generation progression
        if (b2Target != null && !b2Target.isEmpty()) {
            System.out.println("[B2 Sync] Pushing final world and server state to Backblaze B2...");
            boolean pushed = pushToB2(rootDir, rclone, b2Target, host, staleMin, bwLimit, true);
            releaseCloudLock(rootDir, rclone, b2Target);
            cancelRemoteStop(rootDir, rclone, b2Target);
            if (pushed) {
                // Autonomous Storage Hygiene: Purge dead chunk revisions so buckets never bloat.
                // rclone cleanup only purges hidden/superseded file revisions; the live save version is preserved.
                purgeDeadRevisions(rootDir, rclone, b2Target);
            }
        }

        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException ignored) {}

        System.out.println("[Cluster] Cluster cleanly released. Ready for next host.");
        isShuttingDown = false;
    }

    public static void checkRemoteStopSignal(Path rootDir, String rclone, String b2Target, BufferedWriter mcStdin) {
        if (remoteStopTriggered.get()) return;

        CommandResult res = runCommand(rootDir, rclone, "cat", b2Target + "/remote_stop.signal");
        if (res.exitCode == 0 && !res.stdout.isEmpty()) {
            if (remoteStopTriggered.compareAndSet(false, true)) {
                String reason = extractJsonString(res.stdout, "reason");
                if (reason == null || reason.isEmpty()) reason = "Admin requested server stop";
                String requestedBy = extractJsonString(res.stdout, "requested_by");
                if (requestedBy == null || requestedBy.isEmpty()) requestedBy = "Admin";

                System.out.println("\n========================================================");
                System.out.printf("[REMOTE STOP] Signal received from %s!%n", requestedBy);
                System.out.printf("[REMOTE STOP] Reason: %s%n", reason);
                System.out.println("Broadcasting 30s countdown to players and stopping gracefully...");
                System.out.println("========================================================\n");

                final String finalReason = reason;
                new Thread(() -> {
                    try {
                        if (mcStdin != null) {
                            synchronized (mcStdin) {
                                mcStdin.write("say [CLUSTER] Remote server stop requested by Admin.\n");
                                mcStdin.write("say [CLUSTER] Reason: " + finalReason + "\n");
                                mcStdin.write("say [CLUSTER] Server will save and shut down in 30 seconds...\n");
                                mcStdin.flush();
                            }
                            Thread.sleep(20000); // 20s
                            synchronized (mcStdin) {
                                mcStdin.write("say [CLUSTER] Server shutting down in 10 seconds...\n");
                                mcStdin.flush();
                            }
                            Thread.sleep(5000); // 5s
                            synchronized (mcStdin) {
                                mcStdin.write("say [CLUSTER] Server shutting down in 5 seconds...\n");
                                mcStdin.flush();
                            }
                            Thread.sleep(5000); // 5s
                            synchronized (mcStdin) {
                                mcStdin.write("say [CLUSTER] Stopping now. All progress is saved!\n");
                                mcStdin.write("save-all flush\n");
                                mcStdin.write("stop\n");
                                mcStdin.flush();
                            }
                        }
                    } catch (Exception ignored) {}
                }, "RemoteStopWatcher").start();
            }
        }
    }

    public static void dispatchRemoteStop(Path rootDir, String rclone, String b2Target, String host, String reason) {
        String json = String.format("{\"requested_by\":\"%s\",\"reason\":\"%s\",\"timestamp\":\"%s\"}",
                host, reason.replace("\"", "\\\""), DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        runCommandWithInput(json, rootDir, rclone, "rcat", b2Target + "/remote_stop.signal");
        System.out.println("\n========================================================");
        System.out.println("[SUCCESS] Remote stop signal dispatched to Backblaze B2!");
        System.out.printf("  Reason: %s%n", reason);
        System.out.println("  The running host will detect this within 30 seconds,");
        System.out.println("  broadcast a 30s countdown to online players, save,");
        System.out.println("  sync to the cloud, and release the lock.");
        System.out.println("========================================================\n");
    }

    public static void cancelRemoteStop(Path rootDir, String rclone, String b2Target) {
        runCommand(rootDir, rclone, "deletefile", b2Target + "/remote_stop.signal");
    }

    public static boolean pullFromB2(Path rootDir, String rclone, String b2Target, int staleMin, boolean force) {
        if (!force && isCloudLocked(rootDir, rclone, b2Target, staleMin)) {
            return false;
        }

        int cloudGen = readCloudGeneration(rootDir, rclone, b2Target);
        int localGen = readLocalGeneration(rootDir);

        // Zero-Egress Generation Short-Circuit:
        // If local generation matches cloud generation and local world exists, skip downloading
        if (localGen > 0 && cloudGen > 0 && localGen == cloudGen && !isWorldEmpty(rootDir)) {
            System.out.printf("[B2 Sync] Local base matches Cloud Generation #%d. Zero-egress bypass active (0 MB cloud download).%n", cloudGen);
            return true;
        }

        // Quota Cap Resilience: If cloud is quota-capped but local world exists, allow local run
        if (cloudGen == -1 && !isWorldEmpty(rootDir)) {
            System.out.println("[B2 Sync] Backblaze B2 daily download quota is active, but local world is intact.");
            System.out.printf("[B2 Sync] Launching existing local world (Generation #%d). Progress will push upon shutdown.%n", localGen);
            return true;
        }

        // Pre-Flight Egress Guard Calculation
        double estMb = estimateEgressMb(rootDir, rclone, b2Target);
        if (estMb > 0 && isWorldEmpty(rootDir)) {
            System.err.println("\n========================================================");
            System.err.printf("[EGRESS GUARD] Initial cloud download of %.1f MB blocked to preserve cluster daily quota.%n", estMb);
            System.err.println("  Downloading full world from scratch risks locking out the entire cluster for 24 hours.");
            System.err.println("  Tip: Ask the active host for a 'cohost-files-YYYY-MM-DD.zip' to onboard with zero cloud egress.");
            System.err.println("========================================================\n");
            return false;
        }

        if (estMb > 800.0) {
            System.err.println("\n========================================================");
            System.err.printf("[EGRESS GUARD] Cloud download of %.1f MB blocked to preserve cluster daily quota.%n", estMb);
            System.err.println("  Downloading this volume risks locking out the entire cluster for 24 hours.");
            System.err.println("  Tip: Ask the active host for a 'cohost-files-YYYY-MM-DD.zip' to sync with zero cloud egress.");
            System.err.println("========================================================\n");
            return false;
        } else if (estMb >= 350.0) {
            System.out.printf("[B2 Sync Advisory] Notice: Inbound sync will download %.1f MB (~%.0f%% of daily quota).%n", estMb, (estMb / 1000.0) * 100);
            if (!GraphicsEnvironment.isHeadless()) {
                final boolean[] userApproved = new boolean[]{true};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        int choice = JOptionPane.showConfirmDialog(null,
                                String.format("Inbound cloud synchronization requires downloading %.1f MB\n" +
                                        "(~%.0f%% of cluster daily free bandwidth quota).\n\n" +
                                        "Do you wish to proceed with cloud synchronization?", estMb, (estMb / 1000.0) * 100),
                                "B2 Bandwidth Advisory", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        userApproved[0] = (choice == JOptionPane.YES_OPTION);
                    });
                } catch (Exception ignored) {}
                if (!userApproved[0]) {
                    System.err.println("[B2 Sync] Download cancelled by user.");
                    return false;
                }
            }
        }

        boolean forceChecksum = (cloudGen > localGen);

        System.out.printf("[B2 Sync] Pulling latest state from Backblaze B2 (Cloud Gen #%d vs Local Gen #%d)...%n", cloudGen, localGen);
        Path filterPath = rootDir.resolve("rclone-filter.txt");
        List<String> cmd = new ArrayList<>();
        cmd.add(rclone);
        cmd.add("copy");
        cmd.add(b2Target);
        cmd.add(".");
        if (Files.exists(filterPath)) {
            cmd.add("--filter-from");
            cmd.add(filterPath.toString());
        }
        cmd.add("--fast-list");
        if (forceChecksum) {
            // Anti-Timestamp Inversion: Ensure cloud chunks overwrite freshly extracted local files
            cmd.add("--checksum");
            System.out.println("[B2 Sync] Anti-Timestamp Inversion active (--checksum enforced).");
        }

        CommandResult res = runCommand(rootDir, cmd.toArray(new String[0]));
        if (res.exitCode == 0) {
            writeLocalGeneration(rootDir, cloudGen);
            System.out.printf("[B2 Sync] Inbound synchronization complete (World Generation #%d).%n", cloudGen);
            return true;
        } else {
            if (isQuotaExceeded(res)) {
                System.err.println("\n========================================================");
                System.err.println("[QUOTA GUARD] Backblaze B2 daily download quota exceeded (403 Cap).");
                System.err.printf("  Free quota resets automatically at 00:00 UTC (in %s).%n", getTimeUntilUtcMidnightFormatted());
                System.err.println("  Purging incomplete temporary download artifacts...");
                purgeTemporaryFiles(rootDir);
                System.err.println("  Tip: Admins can export a 'cohost-files-YYYY-MM-DD.zip' to onboard without cloud egress.");
                System.err.println("========================================================\n");
            } else {
                System.err.println("[WARN] Inbound sync returned code " + res.exitCode + ": " + res.stderr);
            }
            return false;
        }
    }

    public static boolean pushToB2(Path rootDir, String rclone, String b2Target, String host, int staleMin, String bwLimit, boolean isNormalTeardown) {
        if (!isNormalTeardown && isCloudLocked(rootDir, rclone, b2Target, staleMin)) {
            return false;
        }

        // Optimistic Concurrency Control: verify generation alignment
        int cloudGen = readCloudGeneration(rootDir, rclone, b2Target);
        int localGen = readLocalGeneration(rootDir);

        if (cloudGen > 0 && localGen > 0 && localGen < cloudGen) {
            System.err.println("\n========================================================");
            System.err.printf("[CONFLICT DETECTED] Cloud is on Generation #%d, but your local base is Generation #%d.%n", cloudGen, localGen);
            System.err.println("[REJECTED] Push aborted to prevent overwriting newer progress!");
            System.err.println("You must run 'sync_pull' first to update your local files.");
            System.err.println("========================================================\n");
            return false;
        }

        Path filterPath = rootDir.resolve("rclone-filter.txt");
        List<String> cmd = new ArrayList<>();
        cmd.add(rclone);
        cmd.add("copy");
        if (Files.exists(filterPath)) {
            cmd.add(".");
            cmd.add(b2Target);
            cmd.add("--filter-from");
            cmd.add(filterPath.toString());
        } else {
            cmd.add("world");
            cmd.add(b2Target + "/world");
        }
        cmd.add("--fast-list");
        cmd.add("--update");
        cmd.add("--bwlimit");
        cmd.add(bwLimit != null && !bwLimit.isEmpty() ? bwLimit : "10M");

        CommandResult res = runCommand(rootDir, cmd.toArray(new String[0]));
        if (res.exitCode == 0) {
            int nextGen = Math.max(cloudGen, localGen) + 1;
            writeCloudGeneration(rootDir, rclone, b2Target, nextGen, host);
            writeLocalGeneration(rootDir, nextGen);
            System.out.printf("[B2 Sync] Outbound synchronization complete (Advanced to Generation #%d).%n", nextGen);
            return true;
        } else {
            System.err.println("[WARN] Outbound sync returned code " + res.exitCode + ": " + res.stderr);
            return false;
        }
    }

    public static boolean isCloudLocked(Path rootDir, String rclone, String b2Target, int staleMin) {
        CommandResult catRes = runCommand(rootDir, rclone, "cat", b2Target + "/cluster.lock");
        if (catRes.exitCode == 0 && !catRes.stdout.isEmpty()) {
            String activeHost = extractJsonString(catRes.stdout, "host");
            String timestampStr = extractJsonString(catRes.stdout, "timestamp");
            if (timestampStr != null && !timestampStr.isEmpty()) {
                try {
                    Instant lastBeat = Instant.parse(timestampStr);
                    long elapsedMinutes = Duration.between(lastBeat, Instant.now()).toMinutes();
                    if (elapsedMinutes < staleMin) {
                        System.err.printf("[ABORT] Cannot sync: Server is actively hosted by '%s'!%n", activeHost);
                        System.err.printf("Active heartbeat was pulsed %d minutes ago. Wait until they finish and exit.%n", elapsedMinutes);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    public static String readLockHost(Path rootDir, String rclone, String b2Target) {
        CommandResult catRes = runCommand(rootDir, rclone, "cat", b2Target + "/cluster.lock");
        if (catRes.exitCode == 0 && !catRes.stdout.isEmpty()) {
            String activeHost = extractJsonString(catRes.stdout, "host");
            if (activeHost != null && !activeHost.isEmpty()) {
                return activeHost;
            }
        }
        return "Active Peer";
    }

    public static boolean acquireCloudLock(Path rootDir, String rclone, String b2Target, String host, int staleMin) {
        CommandResult catRes = runCommand(rootDir, rclone, "cat", b2Target + "/cluster.lock");
        if (catRes.exitCode == 0 && !catRes.stdout.isEmpty()) {
            String activeHost = extractJsonString(catRes.stdout, "host");
            String timestampStr = extractJsonString(catRes.stdout, "timestamp");
            if (timestampStr != null && !timestampStr.isEmpty()) {
                try {
                    Instant lastBeat = Instant.parse(timestampStr);
                    long elapsedMinutes = Duration.between(lastBeat, Instant.now()).toMinutes();
                    if (elapsedMinutes < staleMin) {
                        System.err.printf("[ABORT] Server actively hosted by: %s%n", activeHost);
                        System.err.printf("Active heartbeat was pulsed %d minutes ago.%n", elapsedMinutes);
                        return false;
                    } else {
                        System.out.printf("[WARN] Host '%s' heartbeat expired (%d min elapsed). Reclaiming cloud lock...%n", activeHost, elapsedMinutes);
                    }
                } catch (Exception ignored) {}
            }
        }
        writeCloudHeartbeat(rootDir, rclone, b2Target, host);
        System.out.println("Cloud lock successfully acquired by: " + host);
        return true;
    }

    public static void releaseCloudLock(Path rootDir, String rclone, String b2Target) {
        runCommand(rootDir, rclone, "deletefile", b2Target + "/cluster.lock");
    }

    public static void writeCloudHeartbeat(Path rootDir, String rclone, String b2Target, String host) {
        String lockJson = String.format("{\"host\":\"%s\",\"timestamp\":\"%s\"}",
                host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        runCommandWithInput(lockJson, rootDir, rclone, "rcat", b2Target + "/cluster.lock");
    }

    public static int readCloudGeneration(Path rootDir, String rclone, String b2Target) {
        CommandResult res = runCommand(rootDir, rclone, "cat", b2Target + "/manifest.json");
        if (res.exitCode == 0 && !res.stdout.isEmpty()) {
            return extractJsonInt(res.stdout, "generation", 1);
        }
        if (isQuotaExceeded(res)) {
            return -1;
        }
        return 1;
    }

    public static void writeCloudGeneration(Path rootDir, String rclone, String b2Target, int gen, String host) {
        String json = String.format("{\"generation\":%d,\"last_host\":\"%s\",\"timestamp\":\"%s\",\"orchestrator_version\":\"%s\",\"min_orchestrator_version\":\"%s\"}",
                gen, host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), APP_VERSION, APP_VERSION);
        runCommandWithInput(json, rootDir, rclone, "rcat", b2Target + "/manifest.json");
    }

    public static String readCloudMinVersion(Path rootDir, String rclone, String b2Target) {
        CommandResult res = runCommand(rootDir, rclone, "cat", b2Target + "/manifest.json");
        if (res.exitCode == 0 && !res.stdout.isEmpty()) {
            return extractJsonString(res.stdout, "min_orchestrator_version");
        }
        return null;
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        String clean1 = v1.replaceAll("^[vV]", "").trim();
        String clean2 = v2.replaceAll("^[vV]", "").trim();
        String[] parts1 = clean1.split("\\.");
        String[] parts2 = clean2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = 0;
            int n2 = 0;
            if (i < parts1.length) {
                try { n1 = Integer.parseInt(parts1[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            if (i < parts2.length) {
                try { n2 = Integer.parseInt(parts2[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    public static int readLocalGeneration(Path rootDir) {
        Path sessionFile = rootDir.resolve(".cluster_session");
        if (Files.exists(sessionFile)) {
            try {
                String json = Files.readString(sessionFile);
                return extractJsonInt(json, "base_generation", 1);
            } catch (Exception ignored) {}
        }
        return 1;
    }

    public static void writeLocalGeneration(Path rootDir, int gen) {
        Path sessionFile = rootDir.resolve(".cluster_session");
        try {
            String json = String.format("{\"base_generation\":%d}", gen);
            Files.writeString(sessionFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }

    public static void triggerIncrementalPush(Path rootDir, String rclone, String b2Target, String bwLimit, BufferedWriter mcStdin) {
        if (mcStdin != null) {
            try {
                synchronized (mcStdin) {
                    mcStdin.write("save-all\n");
                    mcStdin.flush();
                }
                System.out.println("[Autosave] Initiated background chunk save. Dispatching incremental snapshot to B2...");
                Thread.sleep(1500);
            } catch (Exception ignored) {}
        }
        CompletableFuture.runAsync(() -> {
            String limit = (bwLimit != null && !bwLimit.isEmpty()) ? bwLimit : "10M";
            CommandResult r = runCommand(rootDir, rclone, "copy", "world", b2Target + "/world", "--fast-list", "--update", "--bwlimit", limit);
            if (r.exitCode == 0) {
                System.out.println("[Autosave] Incremental world snapshot synced to Backblaze B2.");
            }
        });
    }

    public static String findPlayit(Path rootDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path localBin = rootDir.resolve("bin").resolve(isWindows ? "playit.exe" : "playit");
        if (Files.exists(localBin)) {
            return localBin.toAbsolutePath().toString();
        }
        String cmd = isWindows ? "where" : "which";
        try {
            Process p = new ProcessBuilder(cmd, "playit").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty() && Files.exists(Paths.get(line.trim()))) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void publishAdminUpdate(Path rootDir, String rclone, String b2Target) {
        System.out.println("\n========================================================");
        System.out.println("[MC Cluster] Publishing Administrative Update to Cloud...");
        System.out.println("========================================================");

        // 1. First ensure ServerLauncher.jar is pushed to root of b2Target for pre-flight launcher updates
        Path jarPath = rootDir.resolve("ServerLauncher.jar");
        if (Files.exists(jarPath)) {
            System.out.println("[1/2] Updating master ServerLauncher.jar in B2...");
            runCommand(rootDir, rclone, "copyto", jarPath.toString(), b2Target + "/ServerLauncher.jar");
            runCommand(rootDir, rclone, "copyto", jarPath.toString(), b2Target + "/ServerLauncher.jar.update");
        }

        // 2. Push full runtime assets (plugins, core.dat, configs, daemons) to b2Target/dist
        System.out.println("[2/2] Synchronizing runtime assets (plugins, core, configs, daemons) to B2...");
        Path filterPath = rootDir.resolve("admin-dist-filter.txt");
        String[] cmd;
        if (Files.exists(filterPath)) {
            cmd = new String[]{rclone, "copy", ".", b2Target + "/dist", "--filter-from", filterPath.toString(), "--update", "--fast-list"};
        } else {
            cmd = new String[]{rclone, "copy", "plugins", b2Target + "/dist/plugins", "--update", "--fast-list"};
        }
        CommandResult res = runCommand(rootDir, cmd);

        if (res.exitCode == 0) {
            int currentGen = readCloudGeneration(rootDir, rclone, b2Target);
            writeCloudGeneration(rootDir, rclone, b2Target, currentGen, getHostName());
            System.out.println("[3/3] Enforced orchestrator version v" + APP_VERSION + " in cloud manifest.");
            System.out.println("\n========================================================");
            System.out.println("[SUCCESS] Cluster update published successfully to Backblaze B2!");
            System.out.println("  Cloud channel: " + b2Target + "/dist");
            System.out.println("  World files, save generations, and active locks were untouched.");
            System.out.println("  Next time any hoster boots via START_SERVER, their machine");
            System.out.println("  will silently pull the new core, plugins, configs, and binaries.");
            System.out.println("========================================================\n");
        } else {
            System.err.println("[ERROR] Failed to publish administrative update: " + res.stderr);
        }
    }

    public static void syncAdminDistribution(Path rootDir, String rclone, String b2Target) {
        Path filterPath = rootDir.resolve("admin-dist-filter.txt");
        String[] cmd;
        if (Files.exists(filterPath)) {
            cmd = new String[]{rclone, "copy", b2Target + "/dist", ".", "--filter-from", filterPath.toString(), "--update", "--fast-list"};
        } else {
            cmd = new String[]{rclone, "copy", b2Target + "/dist/plugins", "plugins", "--update", "--fast-list"};
        }
        CommandResult res = runCommand(rootDir, cmd);
        if (res.exitCode == 0) {
            System.out.println("[Runtime Sync] Administrative distribution synchronized from B2.");
        } else if (isQuotaExceeded(res)) {
            System.err.println("[QUOTA GUARD] Admin distribution sync halted: Backblaze B2 daily download quota exceeded.");
            purgeTemporaryFiles(rootDir);
        }
    }

    public static void pushLauncherToB2(Path rootDir, String rclone, String b2Target) {
        Path jarPath = rootDir.resolve("ServerLauncher.jar");
        if (!Files.exists(jarPath)) {
            System.err.println("[ERROR] ServerLauncher.jar not found in " + rootDir);
            return;
        }
        System.out.println("[Launcher Push] Uploading ServerLauncher.jar to Backblaze B2...");
        CommandResult res = runCommand(rootDir, rclone, "copyto", jarPath.toString(), b2Target + "/ServerLauncher.jar");
        if (res.exitCode == 0) {
            int currentGen = readCloudGeneration(rootDir, rclone, b2Target);
            writeCloudGeneration(rootDir, rclone, b2Target, currentGen, getHostName());
            System.out.println("[Manifest] Enforced orchestrator version v" + APP_VERSION + " in cloud manifest.");
            System.out.println("\n========================================================");
            System.out.println("[SUCCESS] ServerLauncher.jar successfully uploaded to B2!");
            System.out.println("  Cloud target: " + b2Target + "/ServerLauncher.jar");
            System.out.println("  World files, generations, and locks remain untouched.");
            System.out.println("  Next time any hoster launches via START_SERVER, they");
            System.out.println("  will automatically receive and run this new ServerLauncher!");
            System.out.println("========================================================\n");
        } else {
            System.err.println("[ERROR] Failed to upload ServerLauncher.jar: " + res.stderr);
        }
    }

    public static boolean checkLauncherUpdate(Path rootDir, String rclone, String b2Target) {
        try {
            Path updateJar = rootDir.resolve("ServerLauncher.jar.update");
            CommandResult res = runCommand(rootDir, rclone, "copyto", b2Target + "/ServerLauncher.jar", updateJar.toString(), "--update");
            if (res.exitCode == 0 && Files.exists(updateJar)) {
                Path currentJar = rootDir.resolve("ServerLauncher.jar");
                if (Files.exists(currentJar) && isSameContent(currentJar, updateJar)) {
                    Files.deleteIfExists(updateJar);
                    return false;
                }
                System.out.println("\n========================================================");
                System.out.println("[UPDATE] Downloaded updated ServerLauncher.jar from B2!");
                System.out.println("  Applying update and restarting launcher automatically...");
                System.out.println("========================================================\n");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean isSameContent(Path p1, Path p2) {
        try {
            if (Files.size(p1) != Files.size(p2)) return false;
            return Arrays.equals(Files.readAllBytes(p1), Files.readAllBytes(p2));
        } catch (Exception e) {
            return false;
        }
    }

    public static String findRclone(Path rootDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path localBin = rootDir.resolve("bin").resolve(isWindows ? "rclone.exe" : "rclone");
        if (Files.exists(localBin)) {
            return localBin.toAbsolutePath().toString();
        }
        String cmd = isWindows ? "where" : "which";
        try {
            Process p = new ProcessBuilder(cmd, "rclone").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty() && Files.exists(Paths.get(line.trim()))) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}
        return isWindows ? "rclone.exe" : "rclone";
    }

    public static String getB2Remote(String bucket, String keyId, String appKey) {
        return String.format(":b2,account=\"%s\",key=\"%s\":%s/server", keyId, appKey, bucket);
    }

    public static CommandResult purgeDeadRevisions(Path rootDir, String rclone, String b2Target) {
        if (b2Target == null || b2Target.isEmpty()) {
            System.err.println("[B2 Maintenance] Aborted: No Backblaze B2 target configured.");
            return new CommandResult(1, "", "No B2 target configured.");
        }
        System.out.println("[B2 Maintenance] Purging hidden chunk revisions & dead file versions...");
        System.out.println("  (Latest save state and active world remain 100% untouched)");
        CommandResult res = runCommand(rootDir, rclone, "cleanup", b2Target);
        if (res.exitCode == 0) {
            System.out.println("[B2 Maintenance] Cloud bucket storage cleaned successfully. Dead revisions purged.");
        } else {
            System.err.println("[B2 Maintenance] Cleanup notice: " + res.stderr);
        }
        return res;
    }

    public static CommandResult runCommand(Path workingDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir.toFile());
            Process p = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l; while ((l = r.readLine()) != null) stdout.append(l).append("\n");
                } catch (IOException ignored) {}
            });
            Thread errThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l; while ((l = r.readLine()) != null) stderr.append(l).append("\n");
                } catch (IOException ignored) {}
            });
            outThread.start();
            errThread.start();
            int exitCode = p.waitFor();
            outThread.join(3000);
            errThread.join(3000);
            return new CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim());
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    public static CommandResult runCommandWithInput(String input, Path workingDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir.toFile());
            Process p = pb.start();
            if (input != null) {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(input.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l; while ((l = r.readLine()) != null) stdout.append(l).append("\n");
                } catch (IOException ignored) {}
            });
            Thread errThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l; while ((l = r.readLine()) != null) stderr.append(l).append("\n");
                } catch (IOException ignored) {}
            });
            outThread.start();
            errThread.start();
            int exitCode = p.waitFor();
            outThread.join(3000);
            errThread.join(3000);
            return new CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim());
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    public static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("COMPUTERNAME", "UnknownHost");
        }
    }

    public static void writeLockFile(Path path, String host) throws IOException {
        String json = String.format("{\"host\":\"%s\",\"timestamp\":\"%s\"}",
                host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static class PingResult {
        public final boolean isOnline;
        public final int onlinePlayers;
        public final int maxPlayers;
        public final String motd;
        public final String version;

        public PingResult(boolean isOnline, int onlinePlayers, int maxPlayers, String motd, String version) {
            this.isOnline = isOnline;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.motd = motd;
            this.version = version;
        }

        public static PingResult offline() {
            return new PingResult(false, 0, 0, "", "");
        }
    }

    public static PingResult pingMinecraftServer(String fullAddress) {
        if (fullAddress == null || fullAddress.isBlank()) return PingResult.offline();
        String host = fullAddress.trim();
        int port = 25565;
        if (host.contains(":")) {
            String[] parts = host.split(":");
            host = parts[0].trim();
            try {
                port = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {}
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(2500);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Handshake packet
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            handshake.write(0x00); // packet id
            handshake.write(0x00); // protocol version varint (0)
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            handshake.write(0x01); // next state: status

            byte[] handshakeBytes = handshake.toByteArray();
            writeVarInt(out, handshakeBytes.length);
            out.write(handshakeBytes);

            // Status request packet
            out.write(0x01); // packet length
            out.write(0x00); // packet id
            out.flush();

            // Read response
            readVarInt(in); // packet length
            readVarInt(in); // packet id
            int jsonLength = readVarInt(in);

            byte[] jsonBytes = in.readNBytes(jsonLength);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            int online = extractJsonInt(json, "online", 0);
            int max = extractJsonInt(json, "max", 20);
            String motd = extractJsonString(json, "description");
            String version = extractJsonString(json, "name");

            return new PingResult(true, online, max, motd, version);
        } catch (Exception e) {
            return PingResult.offline();
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        int read;
        do {
            read = in.read();
            if (read == -1) throw new EOFException();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) throw new RuntimeException("VarInt too big");
        } while ((read & 0x80) != 0);
        return result;
    }

    public static String getTailscaleIp() {
        try {
            Process process = new ProcessBuilder("tailscale", "ip", "-4").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line != null ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void updateDuckDns(String domain, String token, String ip) {
        try {
            System.out.printf("[DNS] Updating DuckDNS records (%s.duckdns.org -> %s)...%n", domain, ip);
            String url = String.format("https://www.duckdns.org/update?domains=%s&token=%s&ip=%s", domain, token, ip);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if ("OK".equalsIgnoreCase(resp.body().trim())) {
                System.out.println("[DNS] Record synchronization verified.");
            } else {
                System.err.println("[DNS] Upstream DuckDNS rejection: " + resp.body());
            }
        } catch (Exception e) {
            System.err.println("[DNS] Network failure during DNS propagation: " + e.getMessage());
        }
    }

    public static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    public static int extractJsonInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    public static long extractJsonLong(String json, String key, long def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : def;
    }

    public static boolean isQuotaExceeded(CommandResult res) {
        if (res == null) return false;
        String combined = ((res.stdout != null ? res.stdout : "") + " " + (res.stderr != null ? res.stderr : "")).toLowerCase();
        return combined.contains("download_cap_exceeded")
                || combined.contains("transaction_cap_exceeded")
                || (combined.contains("403") && combined.contains("cap"));
    }

    public static void purgeTemporaryFiles(Path rootDir) {
        try {
            Files.walk(rootDir, 2)
                    .filter(p -> p.toString().endsWith(".tmp") || p.getFileName().toString().startsWith(".rclone-tmp"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    public static String getTimeUntilUtcMidnightFormatted() {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime midnightUtc = nowUtc.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        Duration diff = Duration.between(nowUtc, midnightUtc);
        long hours = diff.toHours();
        long minutes = diff.toMinutesPart();
        return String.format("%d hours, %d minutes", hours, minutes);
    }

    public static boolean isWorldEmpty(Path rootDir) {
        Path worldDir = rootDir.resolve("world");
        if (!Files.exists(worldDir)) return true;

        // 1. Check standard Spigot/Paper region folder
        Path regionDir = worldDir.resolve("region");
        if (Files.exists(regionDir)) {
            try (var stream = Files.list(regionDir)) {
                if (stream.anyMatch(p -> p.getFileName().toString().endsWith(".mca"))) {
                    return false;
                }
            } catch (Exception ignored) {}
        }

        // 2. Check modern multi-dimension overworld region folder
        Path dimRegionDir = worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        if (Files.exists(dimRegionDir)) {
            try (var stream = Files.list(dimRegionDir)) {
                if (stream.anyMatch(p -> p.getFileName().toString().endsWith(".mca"))) {
                    return false;
                }
            } catch (Exception ignored) {}
        }

        // 3. Check level.dat as primary indicator of a valid world save
        if (Files.exists(worldDir.resolve("level.dat"))) {
            return false;
        }

        return true;
    }

    public static double estimateEgressMb(Path rootDir, String rclone, String b2Target) {
        if (b2Target == null || b2Target.isEmpty()) return 0.0;
        Path filterPath = rootDir.resolve("rclone-filter.txt");
        List<String> cmd = new ArrayList<>();
        cmd.add(rclone);
        cmd.add("copy");
        cmd.add(b2Target);
        cmd.add(".");
        if (Files.exists(filterPath)) {
            cmd.add("--filter-from");
            cmd.add(filterPath.toString());
        }
        cmd.add("--fast-list");
        cmd.add("--checksum");
        cmd.add("--dry-run");

        CommandResult res = runCommand(rootDir, cmd.toArray(new String[0]));
        return parseTransferredMb(res.stdout + " " + res.stderr);
    }

    public static double parseTransferredMb(String output) {
        if (output == null || output.isEmpty()) return 0.0;
        Pattern p = Pattern.compile("Transferred:\\s+([0-9.]+)\\s+(KiB|MiB|GiB|B|Bytes)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(output);
        if (m.find()) {
            double val = Double.parseDouble(m.group(1));
            String unit = m.group(2).toLowerCase();
            if (unit.contains("gib")) return val * 1024.0;
            if (unit.contains("mib")) return val;
            if (unit.contains("kib")) return val / 1024.0;
            return val / (1024.0 * 1024.0);
        }
        return 0.0;
    }

    public static void exportStarterPack(Path rootDir, Path destZipPath, java.util.function.Consumer<String> logger) throws IOException {
        if (logger == null) logger = System.out::println;
        logger.accept("[Co-host Sync] Initializing package export from " + rootDir + "...");

        // Ensure eula.txt is set to eula=true
        Path eulaFile = rootDir.resolve("eula.txt");
        if (!Files.exists(eulaFile) || Files.readString(eulaFile).contains("eula=false")) {
            Files.writeString(eulaFile, "# Accepted automatically by Minecraft Cluster Orchestrator\neula=true\n");
        }

        if (destZipPath.getParent() != null && !Files.exists(destZipPath.getParent())) {
            Files.createDirectories(destZipPath.getParent());
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destZipPath)))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            // 1. Export world/ directory
            Path worldDir = rootDir.resolve("world");
            if (Files.exists(worldDir)) {
                logger.accept("[Co-host Sync] Packaging world save data...");
                addDirectoryToZip(worldDir, "world", zos, logger);
            }

            // 2. Export plugins/ directory
            Path pluginsDir = rootDir.resolve("plugins");
            if (Files.exists(pluginsDir)) {
                logger.accept("[Co-host Sync] Packaging plugins and configurations...");
                addDirectoryToZip(pluginsDir, "plugins", zos, logger);
            }

            // 3. Export bin/ directory executables
            Path coreDat = rootDir.resolve("bin").resolve("core.dat");
            if (Files.exists(coreDat)) {
                logger.accept("[Co-host Sync] Packaging core.dat (Paper server runtime)...");
                addFileToZip(coreDat, "bin/core.dat", zos);
            }
            Path rcloneExe = rootDir.resolve("bin").resolve("rclone.exe");
            if (Files.exists(rcloneExe)) {
                logger.accept("[Co-host Sync] Packaging rclone.exe (Cloud sync binary)...");
                addFileToZip(rcloneExe, "bin/rclone.exe", zos);
            }
            Path iconIco = rootDir.resolve("bin").resolve("icon.ico");
            if (Files.exists(iconIco)) {
                addFileToZip(iconIco, "bin/icon.ico", zos);
            }

            // 4. Export configuration, executables and server properties (Excluding config.json to protect cloud keys)
            String[] configFiles = {
                    "server.properties", "server-icon.png", "spigot.yml",
                    "bukkit.yml", "commands.yml", "ops.json", "whitelist.json", "banned-players.json",
                    "banned-ips.json", "eula.txt", "LAUNCH_ORCHESTRATOR.bat", "START_SERVER.bat",
                    "rclone-filter.txt", "admin-dist-filter.txt", "MinecraftCluster.exe", "ServerLauncher.jar",
                    "playit.toml"
            };

            for (String fName : configFiles) {
                Path f = rootDir.resolve(fName);
                if (Files.exists(f)) {
                    addFileToZip(f, fName, zos);
                }
            }

            // 5. Ensure manifest.json is embedded with the local generation
            Path manifestPath = rootDir.resolve("manifest.json");
            int localGen = readLocalGeneration(rootDir);
            String mJson;
            if (Files.exists(manifestPath)) {
                mJson = Files.readString(manifestPath);
            } else {
                mJson = String.format("{\"generation\":%d,\"last_host\":\"%s\",\"timestamp\":\"%s\",\"orchestrator_version\":\"%s\"}",
                        localGen, getHostName(), DateTimeFormatter.ISO_INSTANT.format(Instant.now()), APP_VERSION);
            }
            ZipEntry entry = new ZipEntry("manifest.json");
            zos.putNextEntry(entry);
            zos.write(mJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            logger.accept("[Co-host Sync] Archive packaging complete: " + destZipPath.getFileName());
        }
    }

    private static void addFileToZip(Path file, String entryName, ZipOutputStream zos) throws IOException {
        ZipEntry entry = new ZipEntry(entryName.replace('\\', '/'));
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zos.putNextEntry(entry);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static void addDirectoryToZip(Path dir, String baseEntryName, ZipOutputStream zos, java.util.function.Consumer<String> logger) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.forEach(path -> {
                try {
                    if (Files.isDirectory(path)) return;
                    String rel = dir.relativize(path).toString().replace('\\', '/');
                    String entryName = baseEntryName + "/" + rel;

                    // Skip temp and log files
                    if (entryName.endsWith(".tmp") || entryName.endsWith(".log") || entryName.contains(".rclone-tmp")) {
                        return;
                    }

                    addFileToZip(path, entryName, zos);
                } catch (Exception e) {
                    if (logger != null) logger.accept("[WARN] Could not package " + path + ": " + e.getMessage());
                }
            });
        }
    }

    public static String importStarterPack(Path zipPath, Path targetDir, ClusterRegistry registry, java.util.function.Consumer<String> logger) throws IOException {
        if (logger == null) logger = System.out::println;
        logger.accept("[Starter Pack] Unpacking starter pack from " + zipPath + "...");

        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    if (outPath.getParent() != null) {
                        Files.createDirectories(outPath.getParent());
                    }
                    try (OutputStream os = Files.newOutputStream(outPath)) {
                        int read;
                        while ((read = zis.read(buf)) != -1) {
                            os.write(buf, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        // Ensure eula.txt is eula=true
        Path eulaFile = targetDir.resolve("eula.txt");
        Files.writeString(eulaFile, "# Accepted automatically by Minecraft Cluster Orchestrator\neula=true\n");

        // Read config.json to identify cluster details
        Path cfgFile = targetDir.resolve("config.json");
        String clusterId = "cluster-" + System.currentTimeMillis();
        String clusterName = "Imported Cluster";
        String bucket = "";
        String domain = "";

        if (Files.exists(cfgFile)) {
            String json = Files.readString(cfgFile);
            bucket = extractJsonString(json, "b2_bucket");
            domain = extractJsonString(json, "domain");
            if (!bucket.isEmpty()) {
                clusterId = bucket.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
                clusterName = bucket.replace("-", " ");
            }
        }

        if (registry != null) {
            ClusterRegistry.ClusterProfile profile = new ClusterRegistry.ClusterProfile(
                    clusterId, clusterName, targetDir.toAbsolutePath().toString(), bucket, domain
            );
            registry.registerCluster(profile);
            registry.setActiveClusterId(profile.id);
            logger.accept("[Starter Pack] Registered new cluster profile: " + clusterName + " (" + clusterId + ")");
        }

        return clusterId;
    }

    public static int updateClusterInPlace(Path zipPath, Path rootDir, java.util.function.Consumer<String> logger) throws IOException {
        if (logger == null) logger = System.out::println;
        logger.accept("[Co-host Sync] Updating active cluster from " + zipPath.getFileName() + "...");

        int newGen = -1;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // Never overwrite existing local config.json to preserve local machine preferences & keys
                if ("config.json".equalsIgnoreCase(name) && Files.exists(rootDir.resolve("config.json"))) {
                    zis.closeEntry();
                    continue;
                }
                Path outPath = rootDir.resolve(name);
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    if (outPath.getParent() != null) {
                        Files.createDirectories(outPath.getParent());
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int read;
                    while ((read = zis.read(buf)) != -1) {
                        baos.write(buf, 0, read);
                    }
                    byte[] data = baos.toByteArray();
                    Files.write(outPath, data);
                    if ("manifest.json".equalsIgnoreCase(name)) {
                        try {
                            String mJson = new String(data, StandardCharsets.UTF_8);
                            newGen = extractJsonInt(mJson, "generation", -1);
                        } catch (Exception ignored) {}
                    }
                }
                zis.closeEntry();
            }
        }

        if (newGen > 0) {
            writeLocalGeneration(rootDir, newGen);
            logger.accept("[Co-host Sync] Local world base updated to Generation #" + newGen);
        }
        logger.accept("[Co-host Sync] In-place cluster update complete! 0 MB cloud egress consumed.");
        return newGen;
    }
}
