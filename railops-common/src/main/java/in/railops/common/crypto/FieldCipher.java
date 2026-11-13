package in.railops.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM field encryption. Format: keyId(1 byte) || IV(12) || ciphertext+tag.
 *
 * <p>The key-id byte (added in Phase 6) makes rotation possible: new writes use the current key,
 * reads pick the key by id, and a background job re-encrypts old rows before the old key is retired.
 * The {@code aad} (associated data) binds a ciphertext to its column, so a value copied from
 * {@code mfa_secret} into {@code phone} fails to decrypt instead of being silently accepted.
 */
public final class FieldCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte currentKeyId;
    private final SecretKeySpec[] keys = new SecretKeySpec[256];
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(int currentKeyId, String currentKeyBase64) {
        this.currentKeyId = (byte) currentKeyId;
        addKey(currentKeyId, currentKeyBase64);
    }

    /** Registers an older key so existing values stay readable during rotation. */
    public FieldCipher addKey(int keyId, String keyBase64) {
        keys[keyId & 0xFF] = new SecretKeySpec(decodeKey(keyBase64), "AES");
        return this;
    }

    public byte[] encrypt(String plaintext, String aad) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys[currentKeyId & 0xFF], new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + ct.length).put(currentKeyId).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] blob, String aad) {
        if (blob == null) {
            return null;
        }
        if (blob.length < 1 + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("ciphertext too short");
        }
        SecretKeySpec key = keys[blob[0] & 0xFF];
        if (key == null) {
            throw new IllegalStateException("unknown key id " + (blob[0] & 0xFF));
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (tampered data, wrong key or wrong field)", e);
        }
    }

    public boolean needsReencryption(byte[] blob) {
        return blob != null && blob.length > 0 && blob[0] != currentKeyId;
    }

    private static byte[] decodeKey(String b64) {
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("field encryption key is not configured");
        }
        byte[] key = Base64.getDecoder().decode(b64);
        if (key.length != 32) {
            throw new IllegalStateException("field encryption key must be 256 bits");
        }
        return key;
    }
}
