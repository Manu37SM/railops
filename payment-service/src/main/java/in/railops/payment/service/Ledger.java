package in.railops.payment.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Double-entry postings. Each method writes one balanced transaction; the deferred DB trigger rejects the
 * commit if a bug ever writes an unbalanced one.
 *
 * <pre>
 * capture   Dr GATEWAY_CLEARING   (gateway owes us)      Cr CUSTOMER_PAYABLE (we hold the passenger's money)
 * refund    Dr CUSTOMER_PAYABLE                          Cr GATEWAY_CLEARING
 * </pre>
 * CUSTOMER_PAYABLE is deferred revenue: recognising TICKET_REVENUE / GST_PAYABLE / CANCELLATION_FEE_REVENUE
 * happens at journey completion and is part of the revenue module, not this service.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class Ledger {

    private final JdbcClient jdbc;

    public Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID capture(UUID paymentId, String pnr, BigDecimal amount) {
        UUID txn = UUID.randomUUID();
        post(txn, "GATEWAY_CLEARING", 'D', amount, pnr, paymentId, null, "capture");
        post(txn, "CUSTOMER_PAYABLE", 'C', amount, pnr, paymentId, null, "capture");
        return txn;
    }

    public UUID refund(UUID paymentId, UUID refundId, String pnr, BigDecimal amount) {
        UUID txn = UUID.randomUUID();
        post(txn, "CUSTOMER_PAYABLE", 'D', amount, pnr, paymentId, refundId, "refund");
        post(txn, "GATEWAY_CLEARING", 'C', amount, pnr, paymentId, refundId, "refund");
        return txn;
    }

    private void post(UUID txn, String account, char dir, BigDecimal amount, String pnr, UUID paymentId, UUID refundId,
                      String memo) {
        jdbc.sql("""
                        INSERT INTO payment.ledger_entry (txn_id, account, direction, amount, pnr, payment_id, refund_id, memo)
                        VALUES (:t, :a, :d, :amt, :pnr, :p, :r, :m)
                        """)
                .param("t", txn).param("a", account).param("d", String.valueOf(dir)).param("amt", amount)
                .param("pnr", pnr).param("p", paymentId).param("r", refundId).param("m", memo)
                .update();
    }
}
