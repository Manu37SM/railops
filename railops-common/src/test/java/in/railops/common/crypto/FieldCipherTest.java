package in.railops.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PII at rest. Every property here is one an attacker or a careless refactor would break. */
class FieldCipherTest {

    static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    static final String K2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    final FieldCipher cipher = new FieldCipher(1, K1);

    @Test
    void round_trips_including_non_ascii() {
        byte[] blob = cipher.encrypt("सना क़ुरैशी 9820012345", "booking.contact_phone");
        assertThat(cipher.decrypt(blob, "booking.contact_phone")).isEqualTo("सना क़ुरैशी 9820012345");
    }

    @Test
    void same_plaintext_never_produces_the_same_ciphertext() {
        // A deterministic cipher would let anyone with DB read access join identical phone numbers.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(Base64.getEncoder().encodeToString(cipher.encrypt("9820012345", "a")))).isTrue();
        }
    }

    @Test
    void ciphertext_moved_to_another_column_does_not_decrypt() {
        // AAD binds the value to its column: copying an encrypted email into the phone column must fail.
        byte[] email = cipher.encrypt("sana@example.com", "booking.contact_email");
        assertThatThrownBy(() -> cipher.decrypt(email, "booking.contact_phone")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void any_flipped_bit_is_detected() {
        byte[] blob = cipher.encrypt("9820012345", "a");
        for (int i = 1; i < blob.length; i++) {          // byte 0 is the key id, covered below
            byte[] t = blob.clone();
            t[i] ^= 0x01;
            assertThatThrownBy(() -> cipher.decrypt(t, "a")).as("byte %d", i).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void rotation_old_rows_still_decrypt_new_rows_use_the_new_key() {
        byte[] old = cipher.encrypt("9820012345", "a");
        FieldCipher rotated = new FieldCipher(2, K2).addKey(1, K1);
        assertThat(rotated.decrypt(old, "a")).isEqualTo("9820012345");
        assertThat(rotated.needsReencryption(old)).isTrue();
        byte[] fresh = rotated.encrypt("9820012345", "a");
        assertThat(fresh[0]).isEqualTo((byte) 2);
        assertThat(rotated.needsReencryption(fresh)).isFalse();
    }

    @Test
    void unknown_key_id_fails_closed() {
        byte[] blob = new FieldCipher(7, K2).encrypt("x", "a");
        assertThatThrownBy(() -> cipher.decrypt(blob, "a")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void truncated_blobs_fail_cleanly() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[5], "a")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[0], "a")).isInstanceOf(RuntimeException.class);
    }
}
