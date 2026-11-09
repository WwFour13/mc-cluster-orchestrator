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
        StringBuilder json = new StringBuilder("{");
        json.append("\"b2_bucket\":\"").append(escapeJson(config.bucket())).append("\",");
        json.append("\"b2_key_id\":\"").append(escapeJson(config.keyId())).append("\",");
        json.append("\"b2_application_key\":\"").append(escapeJson(config.appKey())).append("\",");
        json.append("\"stale_timeout_minutes\":").append(config.staleMinutes()).append(",");
        json.append("\"b2_bandwidth_limit\":\"").append(escapeJson(config.bandwidthLimit())).append("\"");
        if (config.domain() != null && !config.domain().isEmpty()) {
            json.append(",\"domain\":\"").append(escapeJson(config.domain())).append("\"");
        }
        if (config.token() != null && !config.token().isEmpty()) {
            json.append(",\"token\":\"").append(escapeJson(config.token())).append("\"");
        }
        json.append("}");

        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        return PREFIX + base64;
    }

    public static ClusterConfig decode(String tokenStr) {
        if (tokenStr == null) return null;
        String raw = tokenStr.trim();
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

            return new ClusterConfig(bucket, keyId, appKey, stale, bw, domain, token);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
