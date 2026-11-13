package in.railops.identity.service;

import org.springframework.http.HttpStatus;

/** Client-visible identity failure. Messages are deliberately generic where detail would aid an attacker. */
public class IdentityException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public IdentityException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static IdentityException authFailed() {
        // One message for unknown user, wrong password, wrong OTP and locked account: no enumeration oracle.
        return new IdentityException("AUTH_FAILED", HttpStatus.UNAUTHORIZED,
                "invalid credentials, or the account is temporarily locked");
    }
}
