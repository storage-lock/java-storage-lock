package lock.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 只读管理客户端。查询持有者、剩余租约和等待队列，不提供强制解锁。 */
public final class AdminClient {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseURL;

    public AdminClient(String baseURL) {
        this.baseURL = baseURL.endsWith("/") ? baseURL.substring(0, baseURL.length() - 1) : baseURL;
    }

    public String listLocks() throws IOException, InterruptedException {
        return get("/locks");
    }

    public String getLock(String lockID) throws IOException, InterruptedException {
        return get("/locks/" + lockID);
    }

    public String waiters(String lockID) throws IOException, InterruptedException {
        return get("/locks/" + lockID + "/waiters");
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseURL + path)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("admin query failed: " + response.statusCode());
        }
        return response.body();
    }
}
