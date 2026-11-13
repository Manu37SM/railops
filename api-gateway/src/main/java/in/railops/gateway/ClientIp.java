package in.railops.gateway;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Who is the client? The answer feeds rate-limit keys and the IP that identity writes into the AUDIT LOG, so it must
 * not be client-controlled.
 *
 * <p>Rule: start from the TCP peer. Only while the current hop is a configured trusted proxy (the web tier, the
 * cloud load balancer) step one entry left in X-Forwarded-For. The first untrusted address is the client. Everything
 * further left was written by the client itself and is ignored. With no trusted proxies configured the header is
 * never read.
 *
 * <p>Optional trusted header (Phase 3B): some platforms put the real client in one header set by their own edge, e.g.
 * Render, where Cloudflare sets {@code True-Client-IP} and the proxy APPENDS to X-Forwarded-For, so walking
 * X-Forwarded-For would stop at a Cloudflare edge address and every visitor would share one rate-limit bucket. When
 * configured, the header is used only if the TCP peer is a trusted proxy and the value is an IP literal.
 *
 * <p>Optional proxy secret (Phase 4D): RailLens' website signs passengers in through its own server, whose address
 * changes and can't be listed as a trusted proxy, so every visitor would share that server's rate-limit bucket. When
 * {@code railops.edge.proxy-secret} is set, a request carrying {@link #PROXY_SECRET_HEADER} with that exact value
 * (compared in constant time) may name the visitor in {@link #PROXY_CLIENT_IP_HEADER}. A wrong or missing secret
 * means both headers are ignored. The gateway removes both before forwarding.
 *
 * <p>Phase 12 finding: the gateway forwarded the client's own X-Forwarded-For and identity
 * ({@code forward-headers-strategy: framework}) took its left-most entry, so anyone could choose the IP recorded
 * against their login attempts.
 */
final class ClientIp {

    static final String PROXY_SECRET_HEADER = "X-RailOps-Proxy-Secret";
    static final String PROXY_CLIENT_IP_HEADER = "X-RailOps-Client-IP";
    /** Short secrets are guessable; refuse to start rather than run with one. */
    static final int MIN_PROXY_SECRET_LENGTH = 32;

    private final List<Cidr> trusted;
    private final String trustedHeader;
    private final byte[] proxySecret;

    ClientIp(String trustedCidrs) {
        this(trustedCidrs, "");
    }

    ClientIp(String trustedCidrs, String trustedHeader) {
        this(trustedCidrs, trustedHeader, "");
    }

    ClientIp(String trustedCidrs, String trustedHeader, String proxySecret) {
        String secret = proxySecret == null ? "" : proxySecret.trim();
        if (!secret.isEmpty() && secret.length() < MIN_PROXY_SECRET_LENGTH) {
            throw new IllegalArgumentException("railops.edge.proxy-secret must be at least " + MIN_PROXY_SECRET_LENGTH + " characters");
        }
        this.proxySecret = secret.isEmpty() ? null : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.trustedHeader = trustedHeader == null ? "" : trustedHeader.trim();
        List<Cidr> t = new ArrayList<>();
        if (trustedCidrs != null) {
            for (String c : trustedCidrs.split("[,\\s]+")) {
                if (!c.isBlank()) {
                    t.add(Cidr.parse(c.trim()));
                }
            }
        }
        this.trusted = List.copyOf(t);
    }

    String resolve(ServerHttpRequest request) {
        String viaProxy = fromSignedProxy(request);
        if (viaProxy != null) {
            return viaProxy;
        }
        InetSocketAddress remote = request.getRemoteAddress();
        String peer = remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
        if (trusted.isEmpty() || !isTrusted(peer)) {
            return peer;
        }
        if (!trustedHeader.isEmpty()) {
            String fromHeader = request.getHeaders().getFirst(trustedHeader);
            if (fromHeader != null && looksLikeIp(fromHeader.trim())) {
                return fromHeader.trim();
            }
        }
        List<String> chain = new ArrayList<>();
        for (String h : request.getHeaders().getOrEmpty("X-Forwarded-For")) {
            Arrays.stream(h.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(chain::add);
        }
        String candidate = peer;
        for (int i = chain.size() - 1; i >= 0 && isTrusted(candidate); i--) {
            String hop = chain.get(i);
            if (!looksLikeIp(hop)) {
                return candidate;          // garbage in the chain: stop at the last address we could verify
            }
            candidate = hop;
        }
        return candidate;
    }

    private String fromSignedProxy(ServerHttpRequest request) {
        if (proxySecret == null) {
            return null;
        }
        String presented = request.getHeaders().getFirst(PROXY_SECRET_HEADER);
        if (presented == null || !java.security.MessageDigest.isEqual(proxySecret,
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return null;
        }
        String ip = request.getHeaders().getFirst(PROXY_CLIENT_IP_HEADER);
        return ip != null && looksLikeIp(ip.trim()) ? ip.trim() : null;
    }

    String trustedHeader() {
        return trustedHeader;
    }

    boolean isTrusted(String ip) {
        if (!looksLikeIp(ip)) {
            return false;
        }
        for (Cidr c : trusted) {
            if (c.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /** Literal IPv4/IPv6 only: never let InetAddress resolve a hostname from a header (DNS lookup = SSRF/latency). */
    static boolean looksLikeIp(String s) {
        return s != null && s.length() <= 45 && (s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.matches("[0-9a-fA-F:.]+") && s.contains(":"));
    }

    record Cidr(BigInteger network, BigInteger mask, int bytes) {

        static Cidr parse(String cidr) {
            String[] p = cidr.split("/");
            if (!looksLikeIp(p[0])) {
                throw new IllegalArgumentException("not an IP literal: " + cidr);
            }
            byte[] addr = bytes(p[0]);
            int bits = p.length > 1 ? Integer.parseInt(p[1]) : addr.length * 8;
            if (bits < 0 || bits > addr.length * 8) {
                throw new IllegalArgumentException("bad prefix: " + cidr);
            }
            BigInteger all = BigInteger.ONE.shiftLeft(addr.length * 8).subtract(BigInteger.ONE);
            BigInteger mask = all.shiftRight(bits).xor(all);
            return new Cidr(new BigInteger(1, addr).and(mask), mask, addr.length);
        }

        boolean contains(String ip) {
            byte[] a = bytes(ip);
            return a.length == bytes && new BigInteger(1, a).and(mask).equals(network);
        }

        static byte[] bytes(String literal) {
            try {
                return InetAddress.getByName(literal).getAddress();   // literal only (checked above): no DNS
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(literal, e);
            }
        }
    }
}
