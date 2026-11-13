package in.railops.payment.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private final WebhookSignature sig = new WebhookSignature("test-secret-0123456789abcdef0123456789");
    private final byte[] body = "{\"id\":\"evt_1\",\"type\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsAFreshCorrectSignature() {
        long ts = Instant.now().getEpochSecond();
        assertThat(sig.verify(Long.toString(ts), sig.sign(ts, body), body, Instant.now())).isTrue();
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndReplays() {
        long ts = Instant.now().getEpochSecond();
        String good = sig.sign(ts, body);
        byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"payment.captured\" }".getBytes(StandardCharsets.UTF_8); // one space
        assertThat(sig.verify(Long.toString(ts), good, tampered, Instant.now())).isFalse();
        assertThat(new WebhookSignature("another-secret-0123456789abcdef0123").verify(Long.toString(ts), good, body, Instant.now())).isFalse();
        long old = ts - 600;
        assertThat(sig.verify(Long.toString(old), sig.sign(old, body), body, Instant.now())).isFalse(); // 10 min old
        assertThat(sig.verify(Long.toString(ts + 1), good, body, Instant.now())).isFalse(); // timestamp is signed too
        assertThat(sig.verify(Long.toString(ts), null, body, Instant.now())).isFalse();
        assertThat(sig.verify("abc", good, body, Instant.now())).isFalse();
    }
}
