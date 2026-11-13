package in.railops.payment.service;

import in.railops.payment.gateway.GatewayPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment intents. Two short transactions around the gateway call, never one long one:
 * T1 insert CREATED (the unique live-per-PNR index arbitrates concurrent callers) → gateway createOrder
 * (idempotent on our payment id) → T2 PENDING + order id. A crash between the steps leaves a CREATED row;
 * the next call for the PNR resumes it, and the gateway returns the same order.
 */
@Service
public class PaymentService {

    public record StatusView(UUID paymentId, String pnr, String status, BigDecimal capturedAmount, String currency) {
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<StatusView> statuses(java.util.List<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc.sql("SELECT id, pnr, status, captured_amount, currency FROM payment.payment WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, i) -> new StatusView(rs.getObject(1, UUID.class), rs.getString(2).trim(), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5).trim()))
                .list();
    }

    public record CreateRequest(String pnr, UUID userId, BigDecimal amount, String currency, Instant payBy) {
    }

    public record Intent(UUID paymentId, String checkoutUrl, BigDecimal amount, String status) {
    }

    private record Row(UUID id, BigDecimal amount, String status, String orderId) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GatewayPort gateway;

    public PaymentService(JdbcClient jdbc, TransactionTemplate tx, GatewayPort gateway) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
    }

    public Intent createOrGet(CreateRequest req) {
        if (req.pnr() == null || !req.pnr().matches("[1-9]\\d{9}") || req.amount() == null || req.amount().signum() <= 0
                || !"INR".equals(req.currency()) || req.amount().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid payment request");
        }
        Row row = liveOrInsert(req);
        if (row.amount().compareTo(req.amount()) != 0) {
            // The fare for a PNR never changes while a payment is live; a different amount means a bug or tampering.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a live payment for this PNR has a different amount");
        }
        if ("CAPTURED".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already paid");
        }
        if (row.orderId() != null) {
            // MONEY: never create a second gateway order for one payment. A customer who pays on an older checkout page
            // would be captured on an order we no longer know -> money taken, booking never confirmed.
            return new Intent(row.id(), gateway.checkoutUrl(row.orderId()), row.amount(), "PENDING");
        }
        GatewayPort.Order order = gateway.createOrder(row.id(), row.amount(), "INR", req.pnr());
        int bound = tx.execute(s -> jdbc.sql("""
                        UPDATE payment.payment SET status = 'PENDING', gateway_order_id = :o, updated_at = now(),
                               version = version + 1
                        WHERE id = :id AND status IN ('CREATED','PENDING') AND gateway_order_id IS NULL
                        """).param("o", order.orderId()).param("id", row.id()).update());
        if (bound == 0) {
            // A concurrent caller bound its order first. Hand out THAT order; ours is never shown to anyone and
            // expires unpaid at the gateway.
            Row winner = tx.execute(s -> byId(row.id()));
            if (winner.orderId() == null || !"PENDING".equals(winner.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is no longer payable");
            }
            return new Intent(winner.id(), gateway.checkoutUrl(winner.orderId()), winner.amount(), "PENDING");
        }
        return new Intent(row.id(), order.checkoutUrl(), row.amount(), "PENDING");
    }

    /**
     * The losing INSERT of a create race violates ux_payment_open_pnr, which ABORTS its PostgreSQL transaction: the
     * re-read must happen in a new one (reading inside the aborted transaction failed with 25P02 -> HTTP 500).
     */
    private Row liveOrInsert(CreateRequest req) {
        try {
            return tx.execute(s -> live(req.pnr()).orElseGet(() -> insert(req)));
        } catch (DuplicateKeyException e) {
            return tx.execute(s -> live(req.pnr()).orElseThrow(() -> e));
        }
    }

    private Row byId(UUID id) {
        return jdbc.sql("SELECT id, amount, status, gateway_order_id FROM payment.payment WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    private Optional<Row> live(String pnr) {
        return jdbc.sql("""
                        SELECT id, amount, status, gateway_order_id FROM payment.payment
                        WHERE pnr = :p AND status IN ('CREATED','PENDING','CAPTURED')
                        """).param("p", pnr)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)))
                .optional();
    }

    private Row insert(CreateRequest req) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                            INSERT INTO payment.payment (id, pnr, user_id, amount, currency, status, gateway, pay_by)
                            VALUES (:id, :pnr, :u, :a, 'INR', 'CREATED', :g, :payBy)
                            """)
                    .param("id", id).param("pnr", req.pnr()).param("u", req.userId()).param("a", req.amount())
                    .param("g", gateway.name())
                    .param("payBy", OffsetDateTime.ofInstant(req.payBy() == null ? Instant.now().plusSeconds(900) : req.payBy(),
                            java.time.ZoneOffset.UTC))
                    .update();
        return new Row(id, req.amount(), "CREATED", null);
    }
}
