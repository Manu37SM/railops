package in.railops.reservation.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency keys, scoped per user.
 *
 * <p>{@link #claim} is the first statement of the business transaction. A concurrent request with the
 * same key blocks inside ON CONFLICT until the first transaction ends: on commit it sees the conflict
 * and reads the stored response; on rollback its own insert succeeds. The unique index serializes
 * duplicates, so no distributed lock is needed.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyRepository {

    public record Stored(byte[] requestHash, String status, Integer responseCode, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True if this transaction now owns the key. */
    public boolean claim(UUID userId, String key, byte[] requestHash) {
        return jdbc.sql("""
                        INSERT INTO reservation.idempotency_key (user_id, idem_key, request_hash, status)
                        VALUES (:u, :k, :h, 'IN_PROGRESS')
                        ON CONFLICT DO NOTHING
                        """)
                .param("u", userId).param("k", key).param("h", requestHash)
                .update() == 1;
    }

    public Optional<Stored> find(UUID userId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, response_code, response_body::text AS body
                        FROM reservation.idempotency_key WHERE user_id = :u AND idem_key = :k
                        """)
                .param("u", userId).param("k", key)
                .query((rs, n) -> new Stored(rs.getBytes("request_hash"), rs.getString("status"),
                        (Integer) rs.getObject("response_code"), rs.getString("body")))
                .optional();
    }

    public void complete(UUID userId, String key, int responseCode, String responseBodyJson) {
        jdbc.sql("""
                        UPDATE reservation.idempotency_key
                        SET status = 'COMPLETED', response_code = :c, response_body = CAST(:b AS jsonb)
                        WHERE user_id = :u AND idem_key = :k
                        """)
                .param("c", responseCode).param("b", responseBodyJson).param("u", userId).param("k", key)
                .update();
    }
}
