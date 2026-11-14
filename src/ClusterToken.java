import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ClusterToken {

    public record ClusterConfig(
            String bucket,
            String keyId,
            String appKey,
            int staleMinutes,
            String bandwidthLimit,
            String domain,
            String token
    ) {}

    public static final String PREFIX = "mc-cluster://";

    public static String encode(ClusterConfig config) {
        String bucket = config.bucket() != null ? config.bucket().trim() : "";
        String keyId = config.keyId() != null ? config.keyId().trim().replaceAll("[^a-zA-Z0-9]", "") : "";
        String appKey = config.appKey() != null ? config.appKey().trim() : "";
        String bwLimit = config.bandwidthLimit() != null && !config.bandwidthLimit().trim().isEmpty() ? config.bandwidthLimit().trim() : "10M";

        StringBuilder json = new StringBuilder("{");
        json.append("\"b2_bucket\":\"").append(escapeJson(bucket)).append("\",");
        json.append("\"b2_key_id\":\"").append(escapeJson(keyId)).append("\",");
        json.append("\"b2_application_key\":\"").append(escapeJson(appKey)).append("\",");
        json.append("\"stale_timeout_minutes\":").append(config.staleMinutes() > 0 ? config.staleMinutes() : 10).append(",");
        json.append("\"b2_bandwidth_limit\":\"").append(escapeJson(bwLimit)).append("\"");
        if (config.domain() != null && !config.domain().trim().isEmpty()) {
            json.append(",\"domain\":\"").append(escapeJson(config.domain().trim())).append("\"");
        }
        if (config.token() != null && !config.token().trim().isEmpty()) {
            json.append(",\"token\":\"").append(escapeJson(config.token().trim())).append("\"");
        }
        json.append("}");

        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        return PREFIX + base64;
    }

    public static ClusterConfig decode(String tokenStr) {
        if (tokenStr == null) return null;
        String raw = tokenStr.trim();
        if (raw.startsWith("```") && raw.endsWith("```") && raw.length() >= 6) {
            raw = raw.substring(3, raw.length() - 3).trim();
        } else if (raw.startsWith("`") && raw.endsWith("`") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        if (raw.startsWith(PREFIX)) {
            raw = raw.substring(PREFIX.length()).trim();
        }

        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            String json = new String(bytes, StandardCharsets.UTF_8);

            String bucket = ServerLauncher.extractJsonString(json, "b2_bucket");
            String keyId = ServerLauncher.extractJsonString(json, "b2_key_id");
            String appKey = ServerLauncher.extractJsonString(json, "b2_application_key");
            int stale = ServerLauncher.extractJsonInt(json, "stale_timeout_minutes", 10);
            String bw = ServerLauncher.extractJsonString(json, "b2_bandwidth_limit");
            if (bw == null || bw.isEmpty()) bw = "10M";

            String domain = ServerLauncher.extractJsonString(json, "domain");
            String token = ServerLauncher.extractJsonString(json, "token");

            if (bucket == null || keyId == null || appKey == null) {
                return null;
            }

            bucket = bucket.trim();
            keyId = keyId.trim().replaceAll("[^a-zA-Z0-9]", "");
            appKey = appKey.trim();
            if (bucket.isEmpty() || keyId.isEmpty() || appKey.isEmpty()) {
                return null;
            }

            return new ClusterConfig(bucket, keyId, appKey, stale, bw.trim(), domain != null ? domain.trim() : null, token != null ? token.trim() : null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
