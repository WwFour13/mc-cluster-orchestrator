import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-Cluster Workspace Registry for Minecraft Cluster Orchestrator.
 * Manages multiple server instances on a single machine, storing cluster definitions
 * in ~/.minecraft-cluster/clusters.json (or local clusters.json).
 */
public class ClusterRegistry {

    private static ClusterRegistry instance;

    private Path registryFilePath;
    private String activeClusterId = "";
    private String storageRoot = "";
    private final List<ClusterProfile> clusters = new ArrayList<>();

    public static class ClusterProfile {
        public String id;
        public String name;
        public String path;
        public String bucket;
        public String serverAddress;
        public long lastActive;

        public ClusterProfile() {
            this.id = "";
            this.name = "";
            this.path = "";
            this.bucket = "";
            this.serverAddress = "";
            this.lastActive = System.currentTimeMillis();
        }

        public ClusterProfile(String id, String name, String path, String bucket, String serverAddress) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : id;
            this.path = path != null ? path : "";
            this.bucket = bucket != null ? bucket : "";
            this.serverAddress = serverAddress != null ? serverAddress : "";
            this.lastActive = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return name != null && !name.isEmpty() ? name : id;
        }

        public Path getDirectory() {
            return Paths.get(path);
        }
    }

    private ClusterRegistry(Path fallbackRoot) {
        // Resolve ~/.minecraft-cluster/clusters.json
        String userHome = System.getProperty("user.home", "");
        Path configDir = Paths.get(userHome, ".minecraft-cluster");
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            this.registryFilePath = configDir.resolve("clusters.json");
        } catch (Exception e) {
            // Fallback to workspace root
            this.registryFilePath = fallbackRoot.resolve("clusters.json");
        }

        // Default storage root for new servers: ~/MinecraftClusters or parent of fallbackRoot
        Path defaultStorage = Paths.get(userHome, "MinecraftClusters");
        this.storageRoot = defaultStorage.toString();

        load(fallbackRoot);
    }

    public static synchronized ClusterRegistry getInstance(Path fallbackRoot) {
        if (instance == null) {
            instance = new ClusterRegistry(fallbackRoot);
        }
        return instance;
    }

    public synchronized List<ClusterProfile> getClusters() {
        return Collections.unmodifiableList(new ArrayList<>(clusters));
    }

    public synchronized ClusterProfile getActiveCluster() {
        for (ClusterProfile cp : clusters) {
            if (cp.id.equalsIgnoreCase(activeClusterId)) {
                return cp;
            }
        }
        if (!clusters.isEmpty()) {
            return clusters.get(0);
        }
        return null;
    }

    public synchronized Path getActiveClusterPath(Path fallback) {
        ClusterProfile active = getActiveCluster();
        if (active != null && !active.path.isEmpty()) {
            Path p = Paths.get(active.path);
            if (Files.exists(p)) {
                return p;
            }
        }
        return fallback;
    }

    public synchronized void setActiveClusterId(String id) {
        this.activeClusterId = id != null ? id : "";
        ClusterProfile cp = getActiveCluster();
        if (cp != null) {
            cp.lastActive = System.currentTimeMillis();
        }
        save();
    }

    public synchronized void registerCluster(ClusterProfile profile) {
        if (profile == null || profile.id.isEmpty()) return;
        boolean replaced = false;
        for (int i = 0; i < clusters.size(); i++) {
            if (clusters.get(i).id.equalsIgnoreCase(profile.id)) {
                clusters.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            clusters.add(profile);
        }
        this.activeClusterId = profile.id;
        save();
    }

    public synchronized void removeCluster(String id) {
        clusters.removeIf(c -> c.id.equalsIgnoreCase(id));
        if (activeClusterId.equalsIgnoreCase(id)) {
            activeClusterId = !clusters.isEmpty() ? clusters.get(0).id : "";
        }
        save();
    }

    public synchronized String getStorageRoot() {
        return storageRoot;
    }

    public synchronized void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
        save();
    }

    public synchronized void load(Path fallbackRoot) {
        if (Files.exists(registryFilePath)) {
            try {
                String content = Files.readString(registryFilePath);
                parseJson(content);
                if (!clusters.isEmpty()) {
                    return;
                }
            } catch (Exception e) {
                System.err.println("[ClusterRegistry] Failed to parse existing clusters.json: " + e.getMessage());
            }
        }

        // Auto-migration: If no clusters are registered, check fallbackRoot/config.json
        Path localCfg = fallbackRoot.resolve("config.json");
        if (Files.exists(localCfg)) {
            try {
                String json = Files.readString(localCfg);
                String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
                String domain = ServerLauncher.extractJsonString(json, "domain");
                String name = "Dorm Craft SMP";
                if (bucket.toLowerCase().contains("dorm")) {
                    name = "Dorm Craft SMP";
                } else if (!bucket.isEmpty()) {
                    name = bucket;
                }

                ClusterProfile defaultProfile = new ClusterProfile(
                        "dorm-craft-smp",
                        name,
                        fallbackRoot.toAbsolutePath().toString(),
                        bucket,
                        domain.isEmpty() ? "jensen-edited.tun.ply.gg" : domain
                );
                clusters.add(defaultProfile);
                activeClusterId = defaultProfile.id;
                if (fallbackRoot.getParent() != null) {
                    this.storageRoot = fallbackRoot.getParent().toString();
                }
                save();
                System.out.println("[ClusterRegistry] Auto-migrated active server to cluster profile: " + name);
            } catch (Exception e) {
                System.err.println("[ClusterRegistry] Auto-migration error: " + e.getMessage());
            }
        }
    }

    public synchronized void save() {
        try {
            if (registryFilePath.getParent() != null && !Files.exists(registryFilePath.getParent())) {
                Files.createDirectories(registryFilePath.getParent());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"active_cluster\": \"").append(escapeJson(activeClusterId)).append("\",\n");
            sb.append("  \"storage_root\": \"").append(escapeJson(storageRoot)).append("\",\n");
            sb.append("  \"clusters\": [\n");

            for (int i = 0; i < clusters.size(); i++) {
                ClusterProfile cp = clusters.get(i);
                sb.append("    {\n");
                sb.append("      \"id\": \"").append(escapeJson(cp.id)).append("\",\n");
                sb.append("      \"name\": \"").append(escapeJson(cp.name)).append("\",\n");
                sb.append("      \"path\": \"").append(escapeJson(cp.path)).append("\",\n");
                sb.append("      \"bucket\": \"").append(escapeJson(cp.bucket)).append("\",\n");
                sb.append("      \"server_address\": \"").append(escapeJson(cp.serverAddress)).append("\",\n");
                sb.append("      \"last_active\": ").append(cp.lastActive).append("\n");
                sb.append("    }").append(i < clusters.size() - 1 ? "," : "").append("\n");
            }

            sb.append("  ]\n");
            sb.append("}\n");

            Files.writeString(registryFilePath, sb.toString());
        } catch (IOException e) {
            System.err.println("[ClusterRegistry] Failed to save clusters.json: " + e.getMessage());
        }
    }

    private void parseJson(String json) {
        clusters.clear();
        this.activeClusterId = ServerLauncher.extractJsonString(json, "active_cluster");
        this.storageRoot = ServerLauncher.extractJsonString(json, "storage_root");

        // Parse clusters array
        Pattern clusterObjPattern = Pattern.compile("\\{[^{}]*\"id\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}", Pattern.DOTALL);
        Matcher matcher = clusterObjPattern.matcher(json);
        while (matcher.find()) {
            String obj = matcher.group(0);
            ClusterProfile cp = new ClusterProfile();
            cp.id = ServerLauncher.extractJsonString(obj, "id");
            cp.name = ServerLauncher.extractJsonString(obj, "name");
            cp.path = ServerLauncher.extractJsonString(obj, "path");
            cp.bucket = ServerLauncher.extractJsonString(obj, "bucket");
            cp.serverAddress = ServerLauncher.extractJsonString(obj, "server_address");
            cp.lastActive = ServerLauncher.extractJsonLong(obj, "last_active", System.currentTimeMillis());
            if (!cp.id.isEmpty()) {
                clusters.add(cp);
            }
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
