package lock.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * storage-lock HTTP 锁客户端（薄网络客户端）。
 *
 * <p>只做 HTTP 调用，不重复实现 CAS 语义。服务端（storage-lock-http-api）把锁的
 * 租约与 fencing token 语义暴露成无状态接口。拿到 ownerId 后必须在租约内周期调用
 * {@link #renew} 续租，否则锁会过期被他人抢占。
 */
public final class LockClient {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseURL;

    public LockClient(String baseURL) {
        this.baseURL = baseURL.endsWith("/") ? baseURL.substring(0, baseURL.length() - 1) : baseURL;
    }

    /** 锁被其他 owner 持有，获取失败（HTTP 409）。 */
    public static final class LockBusyException extends IOException {
        public LockBusyException(String message) {
            super(message);
        }
    }

    /** 获取锁。成功返回 {owner_id, fencing_token, lease_expire}。 */
    public AcquireResult acquire(String lockId, String ownerId, String ttl) throws IOException, InterruptedException {
        Map<String, String> body = Map.of(
                "owner_id", ownerId == null ? "" : ownerId,
                "ttl", ttl == null ? "" : ttl,
                "mode", "write");
        return new AcquireResult(doPost("/locks/" + lockId + "/acquire", body));
    }

    /** 续租，延长租约。返回新的 {owner_id, lease_expire}。 */
    public AcquireResult renew(String lockId, String ownerId) throws IOException, InterruptedException {
        return new AcquireResult(doPost("/locks/" + lockId + "/renew", Map.of("owner_id", ownerId)));
    }

    /** 释放锁。非 owner 释放会抛 IOException。 */
    public void release(String lockId, String ownerId) throws IOException, InterruptedException {
        doPost("/locks/" + lockId + "/release", Map.of("owner_id", ownerId));
    }

    /** 查看锁的持有者、租约、等待队列。 */
    public String inspect(String lockId) throws IOException, InterruptedException {
        return doGet("/locks/" + lockId);
    }

    private String doGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseURL + path)).GET().build();
        return send(request);
    }

    private String doPost(String path, Map<String, String> body) throws IOException, InterruptedException {
        String payload = body.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + escape(e.getValue()) + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "{" + s + "}")
                .orElse("{}");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseURL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            throw new LockBusyException(response.body());
        }
        if (response.statusCode() >= 300) {
            throw new IOException("storage-lock request failed: " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 获取锁成功的响应。 */
    public static final class AcquireResult {
        public final String ownerId;
        public final long fencingToken;
        public final String leaseExpire;

        AcquireResult(String json) {
            this.ownerId = field(json, "owner_id");
            this.fencingToken = parseLong(field(json, "fencing_token"));
            this.leaseExpire = field(json, "lease_expire");
        }

        private static String field(String json, String key) {
            String marker = "\"" + key + "\":";
            int start = json.indexOf(marker);
            if (start < 0) {
                return "";
            }
            start += marker.length();
            if (start < json.length() && json.charAt(start) == '"') {
                int end = json.indexOf('"', start + 1);
                if (end < 0) {
                    return "";
                }
                return json.substring(start + 1, end);
            }
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return json.substring(start, end);
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        @Override
        public String toString() {
            return "AcquireResult{ownerId='" + ownerId + "', fencingToken=" + fencingToken + ", leaseExpire='" + leaseExpire + "'}";
        }
    }
}
