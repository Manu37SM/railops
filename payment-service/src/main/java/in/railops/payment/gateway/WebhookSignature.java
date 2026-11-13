package in.railops.payment.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Webhook authentication: signature = hex(HMAC-SHA256(secret, timestamp + "." + rawBody)).
 *
 * <ul>
 *   <li>Computed over the RAW request bytes. Re-serializing a parsed body changes whitespace/key order
 *       and breaks verification (or, worse, verifies something other than what was sent).</li>
 *   <li>The timestamp is signed and must be within 5 minutes: a captured webhook can't be replayed later.
 *       Replays inside the window are absorbed by the webhook_event dedupe table.</li>
 *   <li>Constant-time comparison: no timing oracle on the signature.</li>
 * </ul>
 */
public final class WebhookSignature {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final byte[] secret;

    public WebhookSignature(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("webhook secret must be at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(long timestampSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String timestampHeader, String signatureHeader, byte[] rawBody, Instant now) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.matches("[0-9a-f]{64}")) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - ts) > TOLERANCE.toSeconds()) {
            return false;
        }
        return MessageDigest.isEqual(sign(ts, rawBody).getBytes(StandardCharsets.US_ASCII),
                signatureHeader.getBytes(StandardCharsets.US_ASCII));
    }
}
