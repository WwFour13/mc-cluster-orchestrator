import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.*;

public class PaperDownloader {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static final String[] SUPPORTED_VERSIONS = {
            "1.21.4", "1.21.3", "1.21.1", "1.20.4", "1.20.2", "1.20.1"
    };

    public static void setupFreshServer(Path rootDir, String version, Consumer<String> statusCallback) throws Exception {
        if (statusCallback == null) statusCallback = s -> {};

        Path binDir = rootDir.resolve("bin");
        Files.createDirectories(binDir);
        Path corePath = binDir.resolve("core.dat");

        statusCallback.accept("Querying PaperMC API for version " + version + "...");
        String buildsUrl = "https://api.papermc.io/v2/projects/paper/versions/" + version + "/builds";

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(buildsUrl)).GET().build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IOException("Failed to query PaperMC API (HTTP " + resp.statusCode() + "): " + resp.body());
        }

        String json = resp.body();
        // Parse the highest build number from "builds":[..., 123]
        int latestBuild = -1;
        Pattern p = Pattern.compile("\"build\":\\s*(\\d+)");
        Matcher m = p.matcher(json);
        while (m.find()) {
            try {
                int b = Integer.parseInt(m.group(1));
                if (b > latestBuild) latestBuild = b;
            } catch (NumberFormatException ignored) {}
        }

        if (latestBuild == -1) {
            // Alternative regex for integer array: [1,2,3,4]
            Pattern arrayPattern = Pattern.compile("(\\d+)");
            int buildsIdx = json.indexOf("\"builds\":");
            if (buildsIdx != -1) {
                Matcher am = arrayPattern.matcher(json.substring(buildsIdx));
                while (am.find()) {
                    try {
                        int b = Integer.parseInt(am.group(1));
                        if (b > latestBuild) latestBuild = b;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (latestBuild == -1) {
            throw new IOException("Could not find a valid build for Paper " + version);
        }

        String jarName = "paper-" + version + "-" + latestBuild + ".jar";
        String downloadUrl = "https://api.papermc.io/v2/projects/paper/versions/" + version + "/builds/" + latestBuild + "/downloads/" + jarName;

        statusCallback.accept("Downloading Paper " + version + " (Build #" + latestBuild + ")...");
        HttpRequest dlReq = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build();
        HttpResponse<InputStream> dlResp = HTTP_CLIENT.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());

        if (dlResp.statusCode() != 200) {
            throw new IOException("Failed to download Paper binary (HTTP " + dlResp.statusCode() + ")");
        }

        try (InputStream in = dlResp.body()) {
            Files.copy(in, corePath, StandardCopyOption.REPLACE_EXISTING);
        }

        statusCallback.accept("Provisioning eula.txt and default server properties...");
        Path eulaPath = rootDir.resolve("eula.txt");
        Files.writeString(eulaPath, "# Automatically accepted by MC Cluster Orchestrator\neula=true\n");

        Path propsPath = rootDir.resolve("server.properties");
        if (!Files.exists(propsPath)) {
            String defaultProps = """
                    server-port=25565
                    online-mode=true
                    enable-rcon=false
                    motd=\\u00a7aMinecraft Cluster Server\\u00a7r - Shared Distributed Cloud
                    difficulty=normal
                    gamemode=survival
                    max-players=20
                    spawn-protection=0
                    sync-chunk-writes=true
                    network-compression-threshold=256
                    simulation-distance=8
                    view-distance=10
                    """;
            Files.writeString(propsPath, defaultProps);
        }

        statusCallback.accept("Fresh server setup complete for Paper " + version + "!");
    }

    public static void adoptExistingServer(Path rootDir, Path existingServerDir, Consumer<String> statusCallback) throws Exception {
        if (statusCallback == null) statusCallback = s -> {};

        if (!Files.isDirectory(existingServerDir)) {
            throw new IOException("Selected path is not a directory: " + existingServerDir);
        }

        statusCallback.accept("Scanning existing server folder: " + existingServerDir.getFileName() + "...");

        // 1. Find server jar
        Path foundJar = null;
        try (var stream = Files.list(existingServerDir)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".jar") && !name.contains("launcher") && !name.contains("installer") && !name.contains("backup")) {
                    if (name.contains("paper") || name.contains("spigot") || name.contains("server") || name.contains("purpur") || name.contains("fabric")) {
                        foundJar = file;
                        break;
                    }
                    if (foundJar == null) foundJar = file;
                }
            }
        }

        Path binDir = rootDir.resolve("bin");
        Files.createDirectories(binDir);
        Path coreDat = binDir.resolve("core.dat");

        if (foundJar != null) {
            statusCallback.accept("Importing server binary (" + foundJar.getFileName() + ") -> bin/core.dat...");
            Files.copy(foundJar, coreDat, StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IOException("No server jar found in the selected folder. Expected paper.jar, spigot.jar, or server.jar.");
        }

        // 2. Import config files
        String[] configFiles = {
                "server.properties", "eula.txt", "bukkit.yml", "spigot.yml", "paper-global.yml",
                "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json"
        };
        for (String cfg : configFiles) {
            Path srcFile = existingServerDir.resolve(cfg);
            if (Files.exists(srcFile)) {
                Files.copy(srcFile, rootDir.resolve(cfg), StandardCopyOption.REPLACE_EXISTING);
                statusCallback.accept("Imported " + cfg);
            }
        }

        // Ensure eula is true
        Path eula = rootDir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "eula=true\n");
        }

        // 3. Import plugins directory
        Path srcPlugins = existingServerDir.resolve("plugins");
        if (Files.isDirectory(srcPlugins)) {
            statusCallback.accept("Importing plugins directory...");
            Path destPlugins = rootDir.resolve("plugins");
            Files.createDirectories(destPlugins);
            copyDirectory(srcPlugins, destPlugins);
        }

        // 4. Import world folders
        String[] worldDirs = {"world", "world_nether", "world_the_end"};
        for (String w : worldDirs) {
            Path srcWorld = existingServerDir.resolve(w);
            if (Files.isDirectory(srcWorld)) {
                statusCallback.accept("Importing " + w + "...");
                Path destWorld = rootDir.resolve(w);
                Files.createDirectories(destWorld);
                copyDirectory(srcWorld, destWorld);
            }
        }

        statusCallback.accept("Successfully adopted existing Minecraft server!");
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dest)) Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
