package in.railops.identity.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 Appendix B test vectors (SHA-1 seed "12345678901234567890"), last 6 digits. */
    @Test
    void matchesRfc6238Vectors() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.codeAt(seed, 59L / 30)).isEqualTo("287082");
        assertThat(Totp.codeAt(seed, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.codeAt(seed, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.codeAt(seed, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTrips() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.base32(seed)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.unbase32(Totp.base32(seed))).isEqualTo(seed);
    }

    @Test
    void acceptsDriftButRejectsReplay() {
        String secret = Totp.newSecretBase32();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = now.getEpochSecond() / 30;
        String previousStepCode = Totp.codeAt(Totp.unbase32(secret), step - 1);

        assertThat(Totp.verify(secret, previousStepCode, 0, now)).hasValue(step - 1);        // 30 s drift ok
        assertThat(Totp.verify(secret, previousStepCode, step - 1, now)).isEmpty();          // replay refused
        assertThat(Totp.verify(secret, Totp.codeAt(Totp.unbase32(secret), step - 3), 0, now)).isEmpty(); // too old
        assertThat(Totp.verify(secret, "12345", 0, now)).isEmpty();
    }
}
