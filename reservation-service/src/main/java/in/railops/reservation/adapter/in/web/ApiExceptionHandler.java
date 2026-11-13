package in.railops.reservation.adapter.in.web;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;
import java.util.Map;

/** RFC 9457 problem details with a stable {@code code}. Internal errors never leak messages or stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<ErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCode.RES_RUN_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.RES_BOOKING_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.RES_RUN_NOT_BOOKABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RES_INVALID_JOURNEY, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.RES_QUOTA_INELIGIBLE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.RES_REGRET, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RES_ONLY_IF_CNF_UNMET, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RES_HOLD_EXPIRED, HttpStatus.GONE),
            Map.entry(ErrorCode.RES_INVENTORY_INCONSISTENT, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.IDEM_KEY_REUSED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.IDEM_IN_PROGRESS, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.RES_CANCELLATION_CLOSED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RES_INVALID_CANCELLATION, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.RES_BOOKING_NOT_PAYABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RES_CHART_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PAYMENT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.RES_WAITING_ROOM, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.RES_TIMETABLE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.RES_TIMETABLE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.RES_SIMULATED_PAYMENTS_OFF, HttpStatus.NOT_FOUND));

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_ENTITY);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle(e.code().name());
        pd.setProperty("code", e.code().name());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return badRequest("request validation failed", fields);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class,
            // Phase 5 finding: "?date=tomorrow" or "/runs/abc/availability" fell through to the catch-all as a 500.
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ProblemDetail badInput(Exception e) {
        return badRequest(e instanceof MissingRequestHeaderException m
                ? "missing header " + m.getHeaderName() : "invalid request", List.of());
    }

    /**
     * Method-security denials surface as exceptions inside the controller call, so without these two
     * handlers the catch-all below would turn every 403 into a 500.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail forbidden(Exception e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "not permitted for this resource");
        pd.setProperty("code", "FORBIDDEN");
        return pd;
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ProblemDetail unauthenticated(Exception e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "authentication required");
        pd.setProperty("code", "UNAUTHENTICATED");
        return pd;
    }

    /**
     * Contention and saturation are not bugs: "busy, nothing was changed, try again". INC-002 (Phase 13): an operator's
     * idle psql transaction held a hot pool's row locks; lock_timeout fired and users got 500 INTERNAL (and on-call
     * got paged for a code defect). Not retried server-side: each attempt would wait another lock_timeout on the
     * same lock. The transaction rolled back, so a client retry with the same Idempotency-Key is safe.
     */
    static boolean isBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) {
                return true;                                                     // pool exhausted
            }
            if (t instanceof java.sql.SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState()))) {
                return true;                                                     // lock_timeout, statement_timeout
            }
        }
        return false;
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> dataAccess(org.springframework.dao.DataAccessException e,
                                                                             HttpServletRequest req) {
        if (!isBusy(e)) {
            return org.springframework.http.ResponseEntity.internalServerError().body(internal(e, req));
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "This train is very busy right now; nothing was booked. Please retry.");
        pd.setProperty("code", "DEPENDENCY_UNAVAILABLE");
        pd.setProperty("retryable", true);
        pd.setProperty("correlationId", MDC.get(in.railops.common.web.CorrelationIdFilter.MDC_KEY));
        log.warn("busy (lock/statement/pool timeout) on {}: {}", req.getRequestURI(), rootMessage(e));
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "2").body(pd);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    private ProblemDetail internal(Exception e, HttpServletRequest req) {
        log.error("unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
        pd.setProperty("code", "INTERNAL");
        pd.setProperty("correlationId", MDC.get(in.railops.common.web.CorrelationIdFilter.MDC_KEY));
        return pd;
    }

    /**
     * Unreadable bodies (malformed JSON, wrong field types, unknown enum values) are the CLIENT's mistake: 400.
     * They used to fall through to the 500 handler below, which also logged them as ERRORs and fed 5xx alerts.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(Exception e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is not valid JSON for this endpoint");
        pd.setProperty("code", "MALFORMED_REQUEST");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        // Spring MVC exceptions that already carry their HTTP status (no such route 404, method not allowed 405,
        // unsupported media type 415, missing header 400...) keep it. Previously all of them became 500.
        if (e instanceof org.springframework.web.ErrorResponse er) {
            ProblemDetail pd = er.getBody();
            pd.setProperty("code", "REQUEST_REJECTED");
            return pd;
        }
        log.error("unhandled error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
        pd.setProperty("code", "INTERNAL");
        return pd;
    }

    private static ProblemDetail badRequest(String detail, List<Map<String, String>> fields) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setProperty("code", "VALIDATION_FAILED");
        if (!fields.isEmpty()) {
            pd.setProperty("errors", fields);
        }
        return pd;
    }
}
