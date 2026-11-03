package src;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerLauncher {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static ScheduledExecutorService heartbeatScheduler;
    private static volatile boolean isShuttingDown = false;

    public static void main(String[] args) {
        Path rootDir = Paths.get("").toAbsolutePath();
        Path configPath = rootDir.resolve("config.json");
        Path lockPath = rootDir.resolve(".server_active.lock");
        Path coreBinaryPath = rootDir.resolve("bin").resolve("core.dat");

        if (!Files.exists(configPath)) {
            System.err.println("[ERROR] config.json not found in " + rootDir);
            if (Files.exists(rootDir.resolve("[template]config.json"))) {
                System.err.println("[TIP] Found '[template]config.json'! Copy it to 'config.json' and add your Syncthing API key.");
            }
            return;
        }

        if (!Files.exists(coreBinaryPath)) {
            System.err.println("[ERROR] Server core missing! Expected at: " + coreBinaryPath);
            System.err.println("Ensure your paper.jar is renamed to core.dat inside the 'bin' folder.");
            return;
        }

        try {
            String configJson = Files.readString(configPath);
            String duckDomain = extractJsonString(configJson, "domain");
            String duckToken = extractJsonString(configJson, "token");
            String javaMemory = extractJsonString(configJson, "java_memory");
            String syncthingUrl = extractJsonString(configJson, "api_url");
            String syncthingKey = extractJsonString(configJson, "api_key");
            String syncthingFid = extractJsonString(configJson, "folder_id");

            int staleMin = extractJsonInt(configJson, "stale_timeout_minutes", 10);
            int heartbeatSec = extractJsonInt(configJson, "heartbeat_seconds", 120);

            // 1. Port Verification
            System.out.println("[1/6] Verifying port 25565 availability...");
            if (!isPortAvailable(25565)) {
                System.err.println("[ERROR] Port 25565 is already bound by an active process.");
                return;
            }

            // 2. Lock State Verification & Stale Recovery
            System.out.println("[2/6] Evaluating cluster lock state...");
            String localHost = getHostName();

            if (Files.exists(lockPath)) {
                String lockContent = Files.readString(lockPath);
                String activeHost = extractJsonString(lockContent, "host");
                String timestampStr = extractJsonString(lockContent, "timestamp");

                if (timestampStr != null && !timestampStr.isEmpty()) {
                    Instant lastBeat = Instant.parse(timestampStr);
                    long elapsedMinutes = Duration.between(lastBeat, Instant.now()).toMinutes();

                    if (elapsedMinutes >= staleMin) {
                        System.out.printf("[WARN] Host '%s' heartbeat expired (%d min elapsed). Reclaiming lock...%n", 
                                activeHost, elapsedMinutes);
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

            // 3. Acquire Distributed Lock
            System.out.println("[3/6] Acquiring cluster lock...");
            writeLockFile(lockPath, localHost);
            System.out.println("Lock successfully acquired by: " + localHost);

            // Graceful shutdown hook for interrupt signals / SIGTERM
            Runtime.getRuntime().addShutdownHook(new Thread(() -> 
                cleanup(lockPath, syncthingUrl, syncthingKey, syncthingFid)
            ));

            // 4. Dynamic Mesh DNS Configuration
            String tailscaleIp = getTailscaleIp();
            if (tailscaleIp == null || tailscaleIp.isEmpty()) {
                System.out.println("[WARN] Tailscale not detected or not running. Operating in local mode (127.0.0.1).");
                tailscaleIp = "127.0.0.1";
            } else {
                System.out.println("[Mesh] Resolved Tailscale IPv4: " + tailscaleIp);
            }

            if (duckToken != null && !duckToken.isEmpty() && !duckToken.startsWith("YOUR_")) {
                updateDuckDns(duckDomain, duckToken, tailscaleIp);
            } else {
                System.out.println("[DNS] DuckDNS token unconfigured in config.json. Skipping dynamic DNS update.");
            }

            // 5. Pre-Boot State Verification
            System.out.println("[4/6] Checking Syncthing database for cluster convergence...");
            waitForSyncthing(syncthingUrl, syncthingKey, syncthingFid, "Pre-boot sync", 120);

            // 6. Asynchronous Heartbeat Dispatcher
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    writeLockFile(lockPath, localHost);
                } catch (Exception ignored) {}
            }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);

            // 7. Launch Server Process with Inherited IO
            System.out.printf("[5/6] Executing server runtime (%s heap allocation)...%n", javaMemory);
            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-Dcluster.safe_launch=true",
                    "-Xms" + javaMemory,
                    "-Xmx" + javaMemory,
                    "-jar", coreBinaryPath.toString(),
                    "nogui"
            );
            pb.inheritIO();
            Process mcProcess = pb.start();
            mcProcess.waitFor();

            // 8. Normal Teardown
            cleanup(lockPath, syncthingUrl, syncthingKey, syncthingFid);

        } catch (Exception e) {
            System.err.println("[ERROR] Fatal exception in orchestrator runtime: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static synchronized void cleanup(Path lockPath, String syncthingUrl, String syncthingKey, String syncthingFid) {
        if (isShuttingDown) return;
        isShuttingDown = true;

        System.out.println("\n[6/6] Runtime terminated. Stopping heartbeat daemon...");
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
        }

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
            System.out.println("Cluster lock cleanly released. Cluster ready for next host.");
        } catch (IOException ignored) {}
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
            System.out.printf("[Syncthing] Unconfigured in config.json. Skipping (%s).%n", stage);
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