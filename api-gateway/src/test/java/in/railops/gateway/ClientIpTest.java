package in.railops.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH: the resolved IP lands in identity's audit log and keys the login rate limit. Each case is an attack. */
class ClientIpTest {

    static MockServerHttpRequest from(String peer, String... xff) {
        var b = MockServerHttpRequest.get("/api/v1/auth/login").remoteAddress(new InetSocketAddress(peer, 40000));
        for (String h : xff) b.header("X-Forwarded-For", h);
        return b.build();
    }

    final ClientIp gcp = new ClientIp("172.30.0.0/24, 35.191.0.0/16, 130.211.0.0/22");
    final ClientIp none = new ClientIp("");

    @Test
    void direct_client_cannot_choose_its_ip() {
        assertThat(none.resolve(from("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
        assertThat(gcp.resolve(from("203.0.113.9", "1.2.3.4"))).as("peer not a proxy: header ignored").isEqualTo("203.0.113.9");
    }

    @Test
    void through_web_tier_and_load_balancer_the_real_client_is_found() {
        // client -> GCP LB (appends client) -> nginx (replaces with its peer = LB) -> gateway
        assertThat(gcp.resolve(from("172.30.0.20", "203.0.113.9, 35.191.4.2"))).isEqualTo("203.0.113.9");
    }

    @Test
    void spoofed_entries_left_of_the_first_untrusted_hop_are_ignored() {
        // The client sent "X-Forwarded-For: 10.0.0.1" hoping to look internal; the LB appended its real address.
        assertThat(gcp.resolve(from("35.191.4.2", "10.0.0.1, 198.51.100.7"))).isEqualTo("198.51.100.7");
        // ... or claimed to BE a trusted proxy: still only the rightmost untrusted hop counts.
        assertThat(gcp.resolve(from("35.191.4.2", "35.191.9.9, 198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void gke_chain_through_google_lb_and_nginx_finds_the_client_and_ignores_the_spoof() {
        // client sends "X-Forwarded-For: 10.0.0.1" -> Google ALB appends "<client>, <LB VIP>" -> GFE (35.191.x) connects
        // to nginx -> nginx APPENDS the GFE address -> gateway sees peer = nginx pod.
        ClientIp gke = new ClientIp("35.191.0.0/16,130.211.0.0/22,10.52.0.0/14,34.120.1.2/32");
        assertThat(gke.resolve(from("10.52.3.7", "10.0.0.1, 203.0.113.9, 34.120.1.2, 35.191.8.8"))).isEqualTo("203.0.113.9");
    }

    @Test
    void multiple_headers_are_one_chain_in_order() {
        assertThat(gcp.resolve(from("35.191.4.2", "6.6.6.6", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void garbage_and_hostnames_never_trigger_dns_and_stop_the_walk() {
        assertThat(gcp.resolve(from("35.191.4.2", "evil.example.com"))).isEqualTo("35.191.4.2");
        assertThat(gcp.resolve(from("35.191.4.2", "<script>"))).isEqualTo("35.191.4.2");
        assertThat(gcp.resolve(from("35.191.4.2"))).as("trusted peer, no header").isEqualTo("35.191.4.2");
    }

    @Test
    void ipv6_ranges_work() {
        ClientIp v6 = new ClientIp("2600:1900::/28");
        assertThat(v6.resolve(from("2600:1901::1", "2001:db8::42"))).isEqualTo("2001:db8::42");
        assertThat(v6.isTrusted("2001:db8::1")).isFalse();
        assertThat(v6.isTrusted("35.191.0.1")).as("v4 never matches a v6 range").isFalse();
    }

    @Test
    void misconfiguration_fails_at_startup_not_per_request() {
        assertThatThrownBy(() -> new ClientIp("lb.internal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIp("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Render (Phase 3B): Cloudflare sets True-Client-IP; Render's proxy (10.x) appends to X-Forwarded-For.
    final ClientIp render = new ClientIp("10.0.0.0/8", "True-Client-IP");

    static MockServerHttpRequest fromRender(String peer, String trueClientIp, String xff) {
        var b = MockServerHttpRequest.get("/api/v1/auth/login").remoteAddress(new InetSocketAddress(peer, 40000));
        if (trueClientIp != null) b.header("True-Client-IP", trueClientIp);
        if (xff != null) b.header("X-Forwarded-For", xff);
        return b.build();
    }

    @Test
    void on_render_the_cloudflare_header_names_the_client_not_the_edge() {
        assertThat(render.resolve(fromRender("10.1.2.3", "203.0.113.9", "6.6.6.6, 203.0.113.9, 172.70.1.1")))
                .isEqualTo("203.0.113.9");
        assertThat(new ClientIp("10.0.0.0/8").resolve(fromRender("10.1.2.3", "203.0.113.9", "203.0.113.9, 172.70.1.1")))
                .as("without the header option the walk stops at the Cloudflare edge").isEqualTo("172.70.1.1");
    }

    @Test
    void the_trusted_header_counts_only_from_a_trusted_peer_and_only_as_an_ip_literal() {
        assertThat(render.resolve(fromRender("203.0.113.50", "1.2.3.4", null))).as("direct client").isEqualTo("203.0.113.50");
        assertThat(render.resolve(fromRender("10.1.2.3", "evil.example.com", "198.51.100.7")))
                .as("garbage header: fall back to the walk").isEqualTo("198.51.100.7");
        assertThat(render.trustedHeader()).isEqualTo("True-Client-IP");
    }

    static final String SECRET = "a-shared-secret-that-is-long-enough-0123";

    static MockServerHttpRequest viaProxy(String peer, String secret, String clientIp) {
        var b = MockServerHttpRequest.post("/api/v1/auth/app/login").remoteAddress(new InetSocketAddress(peer, 40000));
        if (secret != null) b.header(ClientIp.PROXY_SECRET_HEADER, secret);
        if (clientIp != null) b.header(ClientIp.PROXY_CLIENT_IP_HEADER, clientIp);
        return b.build();
    }

    @Test
    void the_signed_website_server_names_the_visitor_and_nobody_else_can() {
        ClientIp withSecret = new ClientIp("", "", SECRET);
        assertThat(withSecret.resolve(viaProxy("54.1.2.3", SECRET, "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(withSecret.resolve(viaProxy("54.1.2.3", SECRET + "x", "203.0.113.9"))).as("wrong secret").isEqualTo("54.1.2.3");
        assertThat(withSecret.resolve(viaProxy("54.1.2.3", null, "203.0.113.9"))).as("no secret").isEqualTo("54.1.2.3");
        assertThat(withSecret.resolve(viaProxy("54.1.2.3", SECRET, "evil.example.com"))).as("not an IP").isEqualTo("54.1.2.3");
        assertThat(none.resolve(viaProxy("54.1.2.3", SECRET, "203.0.113.9"))).as("feature off").isEqualTo("54.1.2.3");
    }

    @Test
    void a_short_proxy_secret_is_refused_at_startup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientIp("", "", "too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
