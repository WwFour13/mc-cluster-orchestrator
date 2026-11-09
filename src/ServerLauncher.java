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

public class ServerLauncher {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static ScheduledExecutorService heartbeatScheduler;
    private static volatile boolean isShuttingDown = false;
    private static Process activeMcProcess = null;
    private static Process activePlayitProcess = null;
    private static BufferedWriter activeMcStdin = null;
    private static final AtomicBoolean remoteStopTriggered = new AtomicBoolean(false);

    private static record CommandResult(int exitCode, String stdout, String stderr) {}

    public static void main(String[] args) {
        Path rootDir = Paths.get("").toAbsolutePath();
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

            String syncthingUrl = extractJsonString(configJson, "api_url");
            String syncthingKey = extractJsonString(configJson, "api_key");
            String syncthingFid = extractJsonString(configJson, "folder_id");

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
                        return;

                    default:
                        System.err.println("[ERROR] Unknown argument: " + cmd + " (Available: pull, push, publish-update, push-launcher, update, unlock, status, remote-stop, cancel-stop)");
                        return;
                }
            }

            // Normal Startup Flow
            if (!Files.exists(coreBinaryPath)) {
                System.err.println("[ERROR] Server core missing! Expected at: " + coreBinaryPath);
                System.err.println("Ensure your paper.jar is renamed to core.dat inside the 'bin' folder.");
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
            final String finalSyncthingUrl = syncthingUrl;
            final String finalSyncthingKey = syncthingKey;
            final String finalSyncthingFid = syncthingFid;
            final String finalLocalHost = localHost;
            final int finalStaleMin = staleMin;
            final String finalBwLimit = b2BandwidthLimit;

            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    cleanup(rootDir, lockPath, finalRclone, finalB2Target, finalLocalHost, finalStaleMin, finalBwLimit,
                            finalSyncthingUrl, finalSyncthingKey, finalSyncthingFid, activeMcProcess, activeMcStdin)
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
            } else if (syncthingKey != null && !syncthingKey.isEmpty() && !syncthingKey.startsWith("YOUR_")) {
                System.out.println("[4/6] Checking Syncthing database for cluster convergence...");
                waitForSyncthing(syncthingUrl, syncthingKey, syncthingFid, "Pre-boot sync", 120);
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
                    syncthingUrl, syncthingKey, syncthingFid, activeMcProcess, activeMcStdin);

        } catch (Exception e) {
            System.err.println("[ERROR] Fatal exception in orchestrator runtime: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static synchronized void cleanup(Path rootDir, Path lockPath, String rclone, String b2Target,
                                             String host, int staleMin, String bwLimit,
                                             String syncthingUrl, String syncthingKey, String syncthingFid,
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
            pushToB2(rootDir, rclone, b2Target, host, staleMin, bwLimit, true);
            releaseCloudLock(rootDir, rclone, b2Target);
            cancelRemoteStop(rootDir, rclone, b2Target);
        }

        // Syncthing Fallback (if active)
        if (syncthingKey != null && !syncthingKey.isEmpty() && !syncthingKey.startsWith("YOUR_")) {
            try {
                HttpRequest scanReq = HttpRequest.newBuilder()
                        .uri(URI.create(syncthingUrl + "/rest/db/scan?folder=" + syncthingFid))
                        .header("X-API-Key", syncthingKey)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HTTP_CLIENT.send(scanReq, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
            waitForSyncthing(syncthingUrl, syncthingKey, syncthingFid, "Outbound sync", 60);
        }

        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException ignored) {}

        System.out.println("[Cluster] Cluster cleanly released. Ready for next host.");
    }

    private static void checkRemoteStopSignal(Path rootDir, String rclone, String b2Target, BufferedWriter mcStdin) {
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

    private static void dispatchRemoteStop(Path rootDir, String rclone, String b2Target, String host, String reason) {
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

    private static void cancelRemoteStop(Path rootDir, String rclone, String b2Target) {
        runCommand(rootDir, rclone, "deletefile", b2Target + "/remote_stop.signal");
    }

    private static boolean pullFromB2(Path rootDir, String rclone, String b2Target, int staleMin, boolean force) {
        if (!force && isCloudLocked(rootDir, rclone, b2Target, staleMin)) {
            return false;
        }

        System.out.println("[B2 Sync] Pulling latest state from Backblaze B2...");
        Path filterPath = rootDir.resolve("rclone-filter.txt");
        String[] cmd;
        if (Files.exists(filterPath)) {
            cmd = new String[]{rclone, "copy", b2Target, ".", "--filter-from", filterPath.toString(), "--fast-list"};
        } else {
            cmd = new String[]{rclone, "copy", b2Target + "/world", "world", "--fast-list"};
        }
        CommandResult res = runCommand(rootDir, cmd);
        if (res.exitCode == 0) {
            int cloudGen = readCloudGeneration(rootDir, rclone, b2Target);
            writeLocalGeneration(rootDir, cloudGen);
            System.out.printf("[B2 Sync] Inbound synchronization complete (World Generation #%d).%n", cloudGen);
            return true;
        } else {
            System.err.println("[WARN] Inbound sync returned code " + res.exitCode + ": " + res.stderr);
            return false;
        }
    }

    private static boolean pushToB2(Path rootDir, String rclone, String b2Target, String host, int staleMin, String bwLimit, boolean isNormalTeardown) {
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

    private static boolean isCloudLocked(Path rootDir, String rclone, String b2Target, int staleMin) {
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

    private static boolean acquireCloudLock(Path rootDir, String rclone, String b2Target, String host, int staleMin) {
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

    private static void releaseCloudLock(Path rootDir, String rclone, String b2Target) {
        runCommand(rootDir, rclone, "deletefile", b2Target + "/cluster.lock");
    }

    private static void writeCloudHeartbeat(Path rootDir, String rclone, String b2Target, String host) {
        String lockJson = String.format("{\"host\":\"%s\",\"timestamp\":\"%s\"}",
                host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        runCommandWithInput(lockJson, rootDir, rclone, "rcat", b2Target + "/cluster.lock");
    }

    private static int readCloudGeneration(Path rootDir, String rclone, String b2Target) {
        CommandResult res = runCommand(rootDir, rclone, "cat", b2Target + "/manifest.json");
        if (res.exitCode == 0 && !res.stdout.isEmpty()) {
            return extractJsonInt(res.stdout, "generation", 1);
        }
        return 1;
    }

    private static void writeCloudGeneration(Path rootDir, String rclone, String b2Target, int gen, String host) {
        String json = String.format("{\"generation\":%d,\"last_host\":\"%s\",\"timestamp\":\"%s\"}",
                gen, host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        runCommandWithInput(json, rootDir, rclone, "rcat", b2Target + "/manifest.json");
    }

    private static int readLocalGeneration(Path rootDir) {
        Path sessionFile = rootDir.resolve(".cluster_session");
        if (Files.exists(sessionFile)) {
            try {
                String json = Files.readString(sessionFile);
                return extractJsonInt(json, "base_generation", 1);
            } catch (Exception ignored) {}
        }
        return 1;
    }

    private static void writeLocalGeneration(Path rootDir, int gen) {
        Path sessionFile = rootDir.resolve(".cluster_session");
        try {
            String json = String.format("{\"base_generation\":%d}", gen);
            Files.writeString(sessionFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }

    private static void triggerIncrementalPush(Path rootDir, String rclone, String b2Target, String bwLimit, BufferedWriter mcStdin) {
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

    private static String findPlayit(Path rootDir) {
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

    private static void publishAdminUpdate(Path rootDir, String rclone, String b2Target) {
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

    private static void syncAdminDistribution(Path rootDir, String rclone, String b2Target) {
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
        }
    }

    private static void pushLauncherToB2(Path rootDir, String rclone, String b2Target) {
        Path jarPath = rootDir.resolve("ServerLauncher.jar");
        if (!Files.exists(jarPath)) {
            System.err.println("[ERROR] ServerLauncher.jar not found in " + rootDir);
            return;
        }
        System.out.println("[Launcher Push] Uploading ServerLauncher.jar to Backblaze B2...");
        CommandResult res = runCommand(rootDir, rclone, "copyto", jarPath.toString(), b2Target + "/ServerLauncher.jar");
        if (res.exitCode == 0) {
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

    private static boolean checkLauncherUpdate(Path rootDir, String rclone, String b2Target) {
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

    private static boolean isSameContent(Path p1, Path p2) {
        try {
            if (Files.size(p1) != Files.size(p2)) return false;
            return Arrays.equals(Files.readAllBytes(p1), Files.readAllBytes(p2));
        } catch (Exception e) {
            return false;
        }
    }

    private static String findRclone(Path rootDir) {
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

    private static String getB2Remote(String bucket, String keyId, String appKey) {
        return String.format(":b2,account=\"%s\",key=\"%s\":%s/server", keyId, appKey, bucket);
    }

    private static CommandResult runCommand(Path workingDir, String... command) {
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

    private static CommandResult runCommandWithInput(String input, Path workingDir, String... command) {
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

    private static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("COMPUTERNAME", "UnknownHost");
        }
    }

    private static void writeLockFile(Path path, String host) throws IOException {
        String json = String.format("{\"host\":\"%s\",\"timestamp\":\"%s\"}",
                host, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String getTailscaleIp() {
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

    private static void updateDuckDns(String domain, String token, String ip) {
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

    private static void waitForSyncthing(String url, String key, String fid, String stage, int timeoutSec) {
        if (url == null || url.isEmpty() || key == null || key.isEmpty() || key.startsWith("YOUR_")) {
            return;
        }
        System.out.printf("[Syncthing] Monitoring replication state (%s)...%n", stage);
        int elapsed = 0;
        String statusUri = url + "/rest/db/status?folder=" + fid;

        while (elapsed < timeoutSec) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(statusUri))
                        .header("X-API-Key", key)
                        .GET()
                        .timeout(Duration.ofSeconds(3))
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                String body = resp.body();

                String state = extractJsonString(body, "state");
                long needBytes = extractJsonLong(body, "needBytes", -1);

                if ("idle".equalsIgnoreCase(state) && needBytes == 0) {
                    System.out.println("[Syncthing] Folder state converged: 100% synchronized.");
                    return;
                }

                System.out.printf("[Syncthing] Current State: %s | Inbound Delta: %.2f MB...%n",
                        state, needBytes > 0 ? (needBytes / (1024.0 * 1024.0)) : 0.0);

            } catch (Exception e) {
                if (elapsed == 0) {
                    System.out.printf("[Syncthing] Daemon not reachable at %s. Skipping replication wait.%n", url);
                    return;
                }
                System.out.println("[Syncthing] Awaiting daemon response...");
            }

            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            elapsed += 3;
        }
        System.out.println("[WARN] Replication polling reached timeout ceiling. Continuing execution.");
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static int extractJsonInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static long extractJsonLong(String json, String key, long def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : def;
    }
}
