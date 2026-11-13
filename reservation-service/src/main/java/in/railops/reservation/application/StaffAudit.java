package in.railops.reservation.application;

import in.railops.common.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/**
 * Staff actions and reads go to audit.events.v1 (IAM-3) through the outbox: in the caller's transaction for reads
 * (the read and its audit record commit together), in a fresh one after an action that ran its own transactions.
 * Was three hand-written outbox.append calls in StaffRunController.
 */
@Component
public class StaffAudit {

    static final String TOPIC = "audit.events.v1";

    private final OutboxWriter outbox;
    private final TransactionRunner tx;
    private final Clock clock;

    // ONE constructor: with two and no @Autowired, Spring looks for a default constructor and the service fails to
    // start (Phase 15 regression, caught by the e2e run after test-all.sh had falsely reported PASS).
    public StaffAudit(OutboxWriter outbox, TransactionRunner tx) {
        this.outbox = outbox;
        this.tx = tx;
        this.clock = Clock.systemUTC();
    }

    /** A read: must run inside the transaction that performed it. */
    public void access(String actor, String action, long runId) {
        append(actor, "StaffAccess", action, runId);
    }

    /** An action that already committed: recorded in its own transaction. */
    public void action(String actor, String action, long runId) {
        tx.inNewTransaction(() -> append(actor, "StaffAction", action, runId));
    }

    private void append(String actor, String eventType, String action, long runId) {
        outbox.append(TOPIC, "AuditEvent", actor, eventType,
                Map.of("actorId", actor, "action", action, "targetType", "TrainRun",
                        "targetId", Long.toString(runId), "occurredAt", clock.instant().toString()), Map.of());
    }
}
