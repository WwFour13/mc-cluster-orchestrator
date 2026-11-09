import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.*;

public class GitHubUpdateChecker {

    public static final String DEFAULT_REPO = "tavgu/mc-cluster-orchestrator";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record UpdateInfo(boolean hasUpdate, String currentVersion, String latestVersion, String downloadUrl, String releaseNotes) {}

    public static CompletableFuture<UpdateInfo> checkAsync(String currentVersion, String repo) {
        return CompletableFuture.supplyAsync(() -> checkForUpdate(currentVersion, repo));
    }

    public static UpdateInfo checkForUpdate(String currentVersion, String repo) {
        if (repo == null || repo.isBlank()) repo = DEFAULT_REPO;

        try {
            String url = "https://api.github.com/repos/" + repo + "/releases/latest";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "MC-Cluster-Orchestrator/" + currentVersion)
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new UpdateInfo(false, currentVersion, currentVersion, null, null);
            }

            String body = resp.body();
            String tagName = ServerLauncher.extractJsonString(body, "tag_name");
            if (tagName == null || tagName.isBlank()) {
                return new UpdateInfo(false, currentVersion, currentVersion, null, null);
            }

            String cleanTag = tagName.startsWith("v") || tagName.startsWith("V") ? tagName.substring(1) : tagName;
            boolean isNewer = isNewerVersion(currentVersion, cleanTag);

            String downloadUrl = null;
            // Parse asset browser_download_url for ServerLauncher.jar
            Pattern p = Pattern.compile("\"browser_download_url\":\\s*\"(https://[^\"]+ServerLauncher\\.jar)\"");
            Matcher m = p.matcher(body);
            if (m.find()) {
                downloadUrl = m.group(1);
            } else {
                // Fallback to any jar asset
                Pattern anyJar = Pattern.compile("\"browser_download_url\":\\s*\"(https://[^\"]+\\.jar)\"");
                Matcher jm = anyJar.matcher(body);
                if (jm.find()) {
                    downloadUrl = jm.group(1);
                }
            }

            String releaseNotes = ServerLauncher.extractJsonString(body, "body");
            return new UpdateInfo(isNewer, currentVersion, cleanTag, downloadUrl, releaseNotes);

        } catch (Exception ignored) {
            return new UpdateInfo(false, currentVersion, currentVersion, null, null);
        }
    }

    public static boolean isNewerVersion(String current, String remote) {
        if (current == null || remote == null) return false;
        String curClean = current.replaceAll("[^0-9.]", "").trim();
        String remClean = remote.replaceAll("[^0-9.]", "").trim();

        String[] curParts = curClean.split("\\.");
        String[] remParts = remClean.split("\\.");

        int length = Math.max(curParts.length, remParts.length);
        for (int i = 0; i < length; i++) {
            int c = i < curParts.length && !curParts[i].isEmpty() ? Integer.parseInt(curParts[i]) : 0;
            int r = i < remParts.length && !remParts[i].isEmpty() ? Integer.parseInt(remParts[i]) : 0;
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    public static void downloadUpdate(String downloadUrl, Path rootDir, Consumer<Integer> progressConsumer) throws Exception {
        if (progressConsumer == null) progressConsumer = p -> {};
        if (downloadUrl == null) throw new IllegalArgumentException("Download URL is missing");

        Path updateFile = rootDir.resolve("ServerLauncher.jar.update");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "MC-Cluster-Orchestrator")
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Failed to download update: HTTP " + resp.statusCode());
        }

        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(updateFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                totalRead += read;
                if (contentLength > 0) {
                    int percent = (int) ((totalRead * 100) / contentLength);
                    progressConsumer.accept(percent);
                }
            }
        }
        progressConsumer.accept(100);
    }
}
