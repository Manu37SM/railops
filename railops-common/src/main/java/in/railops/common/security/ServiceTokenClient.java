package in.railops.common.security;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains and caches this service's client_credentials token, refreshing 60 s before expiry.
 *
 * <p>ReentrantLock, not synchronized: a virtual thread blocking on network I/O inside a synchronized
 * block pins its carrier thread (Phase 4 §11). Only one caller refreshes; others wait briefly and then
 * reuse the fresh token, so a burst of requests doesn't stampede the identity service.
 */
public class ServiceTokenClient {

    private record Cached(String token, Instant refreshAfter) {
    }

    private final RestClient client;
    private final String basicAuth;
    private final String scope;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Cached cached;

    public ServiceTokenClient(RestClient.Builder builder, String tokenUri, String clientId, String clientSecret,
                              String scope) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(500));
        rf.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(tokenUri).requestFactory(rf).build();
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        this.scope = scope;
    }

    public String token() {
        Cached c = cached;
        if (c != null && Instant.now().isBefore(c.refreshAfter())) {
            return c.token();
        }
        lock.lock();
        try {
            c = cached;
            if (c != null && Instant.now().isBefore(c.refreshAfter())) {
                return c.token();
            }
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("scope", scope);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.post()
                    .header("Authorization", basicAuth)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("access_token") == null) {
                throw new IllegalStateException("token endpoint returned no access_token");
            }
            long ttl = ((Number) body.get("expires_in")).longValue();
            cached = new Cached((String) body.get("access_token"), Instant.now().plusSeconds(Math.max(5, ttl - 60)));
            return cached.token();
        } finally {
            lock.unlock();
        }
    }

    /** Called on a 401 from a downstream: the token may have been invalidated by key rotation. */
    public void invalidate() {
        cached = null;
    }
}
