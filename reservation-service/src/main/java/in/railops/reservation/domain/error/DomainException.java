package in.railops.reservation.domain.error;

/** A business outcome the caller should see. Never retried by the transaction retry wrapper. */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
