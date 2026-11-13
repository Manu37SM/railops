package in.railops.identity.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** Deterministic HMAC lookup token for phone numbers (last 10 digits), separate key from encryption. */
public final class PhoneTokens {

    private final SecretKeySpec key;

    public PhoneTokens(String keyBase64) {
        byte[] k = keyBase64 == null || keyBase64.isBlank() ? new byte[0] : Base64.getDecoder().decode(keyBase64);
        if (k.length != 32) {
            throw new IllegalStateException("phone HMAC key must be 256 bits");
        }
        this.key = new SecretKeySpec(k, "HmacSHA256");
    }

    public byte[] token(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 10) {
            throw new IllegalArgumentException("phone number too short");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(digits.substring(digits.length() - 10).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
