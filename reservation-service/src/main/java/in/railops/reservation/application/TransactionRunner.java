package in.railops.reservation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs work in a fresh transaction and retries only genuinely transient failures:
 * deadlock victim (40P01) and serialization failure (40001). Business outcomes (exclusion or
 * check violations, DomainException) are never retried.
 *
 * <p>Matching on SQLSTATE rather than Spring's exception subclasses keeps the rule independent of
 * which exception translator is active.
 */
@Component
public class TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionRunner.class);
    private static final Set<String> TRANSIENT = Set.of("40P01", "40001");
    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate requiresNew;
    private final TransactionTemplate nested;

    public TransactionRunner(PlatformTransactionManager tm) {
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public <T> T inNewTransaction(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return requiresNew.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isTransient(e)) {
                    throw e;
                }
                log.warn("transient database failure (attempt {}), retrying: {}", attempt, sqlState(e));
                backoff(attempt);
            }
        }
    }

    public void inNewTransaction(Runnable work) {
        inNewTransaction(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs inside the current transaction behind a savepoint: an exception rolls back only this
     * block. Used where a speculative step (late-payment re-allocation) may need undoing without
     * losing the surrounding work.
     */
    public <T> T inSavepoint(Supplier<T> work) {
        return nested.execute(status -> work.get());
    }

    static boolean isTransient(Throwable e) {
        String state = sqlState(e);
        // Set.of(...).contains(null) throws NPE; non-SQL failures (DomainException) have no state.
        return state != null && TRANSIENT.contains(state);
    }

    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void backoff(int attempt) {
        long millis = ThreadLocalRandom.current().nextLong(5, 25) * attempt;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", ie);
        }
    }
}
