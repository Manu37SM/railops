package in.railops.identity.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s), compatible with standard authenticator apps.
 *
 * <p>{@link #verify} accepts the current step ±1 (clock drift), but only steps strictly greater than the
 * last accepted one: a code observed over someone's shoulder can't be replayed within its 90 s window.
 */
public final class Totp {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static String newSecretBase32() {
        byte[] secret = new byte[20]; // 160 bits, the RFC 4226 recommendation for SHA-1
        RANDOM.nextBytes(secret);
        return base32(secret);
    }

    public static String otpauthUri(String issuer, String account, String secretBase32) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8) + "&algorithm=SHA1&digits=6&period=30";
    }

    /** Returns the matched time step, or empty if the code is wrong, stale or already used. */
    public static OptionalLong verify(String secretBase32, String code, long lastAcceptedStep, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] key = unbase32(secretBase32);
        long current = now.getEpochSecond() / STEP_SECONDS;
        OptionalLong matched = OptionalLong.empty();
        // Check every candidate (no early exit) so timing doesn't reveal which step matched.
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            boolean eq = MessageDigest.isEqual(codeAt(key, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII));
            if (eq && step > lastAcceptedStep && matched.isEmpty()) {
                matched = OptionalLong.of(step);
            }
        }
        return matched;
    }

    static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("invalid base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }
}
