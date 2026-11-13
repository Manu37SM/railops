package in.railops.reservation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.reservation.adapter.out.persistence.IdempotencyRepository;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

/**
 * Generic claim-or-replay for idempotent mutations (cancellation, payment intent). Must be called as the
 * first statement of the business transaction; see {@link IdempotencyRepository} for the blocking semantics.
 * The operation name is part of the hash, so one key can't be replayed across different operations.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyGuard {

    private final IdempotencyRepository repo;
    private final ObjectMapper mapper;

    public IdempotencyGuard(IdempotencyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public <T> Optional<T> claimOrReplay(UUID userId, String key, String operation, Object request, Class<T> type) {
        byte[] hash = hash(operation, request);
        if (repo.claim(userId, key, hash)) {
            return Optional.empty();
        }
        IdempotencyRepository.Stored stored = repo.find(userId, key).orElseThrow();
        if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
            throw new DomainException(ErrorCode.IDEM_KEY_REUSED, "Idempotency-Key was used with a different request");
        }
        if (!"COMPLETED".equals(stored.status())) {
            throw new DomainException(ErrorCode.IDEM_IN_PROGRESS, "request with this Idempotency-Key is in progress");
        }
        try {
            return Optional.of(mapper.readValue(stored.responseBody(), type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response unreadable", e);
        }
    }

    public void complete(UUID userId, String key, int status, Object response) {
        try {
            repo.complete(userId, key, status, mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] hash(String operation, Object request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(operation.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(mapper.writeValueAsBytes(request));
            return md.digest();
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
