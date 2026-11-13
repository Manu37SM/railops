package in.railops.reservation.security;

import in.railops.common.crypto.FieldCipher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Field-level protection for passenger contact data: AES-256-GCM via {@link FieldCipher} (key-id prefixed,
 * column-bound with AAD), plus a separate HMAC key for a deterministic phone lookup token, so "my bookings
 * by phone" works without decrypting rows and the encryption key is never usable for lookups.
 */
public final class ContactProtector {

    static final String AAD_PHONE = "reservation.booking.contact_phone";
    static final String AAD_EMAIL = "reservation.booking.contact_email";

    private final FieldCipher cipher;
    private final SecretKeySpec macKey;

    public ContactProtector(FieldCipher cipher, String macKeyBase64) {
        this.cipher = cipher;
        byte[] mac = macKeyBase64 == null || macKeyBase64.isBlank() ? new byte[0] : Base64.getDecoder().decode(macKeyBase64);
        if (mac.length != 32) {
            throw new IllegalStateException("hmac key must be 256 bits");
        }
        this.macKey = new SecretKeySpec(mac, "HmacSHA256");
    }

    public byte[] encryptPhone(String phone) {
        return cipher.encrypt(phone, AAD_PHONE);
    }

    public byte[] encryptEmail(String email) {
        return cipher.encrypt(email, AAD_EMAIL);
    }

    public String decryptPhone(byte[] blob) {
        return cipher.decrypt(blob, AAD_PHONE);
    }

    public String decryptEmail(byte[] blob) {
        return cipher.decrypt(blob, AAD_EMAIL);
    }

    /** Normalizes to the last 10 digits so "+91 98200 12345" and "9820012345" match. */
    public byte[] phoneLookupToken(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 10) {
            throw new IllegalArgumentException("phone number too short");
        }
        String normalized = digits.substring(digits.length() - 10);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
