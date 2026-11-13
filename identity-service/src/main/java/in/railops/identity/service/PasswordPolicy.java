package in.railops.identity.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * NIST SP 800-63B style: length over composition rules, block known-bad passwords, no forced rotation.
 * The deny list here is a stub; production loads a large breached-password corpus (k-anonymity range
 * queries or a local bloom filter) instead of shipping a list in code.
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128; // bounds hashing cost: Argon2 on a 1 MB "password" is a DoS vector

    private static final Set<String> COMMON = Set.of(
            "password123", "1234567890", "qwertyuiop", "iloveindia", "password@123", "indianrailways",
            "irctc12345", "welcome@123", "admin@1234", "abcd@12345");

    public void check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw weak("password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw weak("password must be at most " + MAX_LENGTH + " characters");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            throw weak("this password is too common");
        }
        if (email != null) {
            String local = email.split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) {
                throw weak("password must not contain your email name");
            }
        }
        if (password.chars().distinct().count() < 4) {
            throw weak("password is too repetitive");
        }
    }

    private static IdentityException weak(String msg) {
        return new IdentityException("WEAK_PASSWORD", HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }
}
