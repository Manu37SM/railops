package in.railops.reservation.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CancellationRepository {

    public record NewCancellation(UUID id, LocalDate runDate, long bookingId, String pnr, List<Integer> seqs,
                                  String kind, BigDecimal paid, BigDecimal charge, BigDecimal refund,
                                  String breakdownJson, String requestedBy) {
    }

    private final JdbcClient jdbc;

    public CancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewCancellation c) {
        jdbc.sql("""
                        INSERT INTO reservation.cancellation
                          (id, run_date, booking_id, pnr, passenger_seqs, kind, total_paid, total_charge,
                           refund_amount, breakdown, requested_by)
                        VALUES (:id, :runDate, :booking, :pnr, string_to_array(:seqs, ',')::smallint[], :kind,
                                :paid, :charge, :refund, CAST(:breakdown AS jsonb), :by)
                        """)
                .param("id", c.id()).param("runDate", c.runDate()).param("booking", c.bookingId()).param("pnr", c.pnr())
                .param("seqs", String.join(",", c.seqs().stream().map(String::valueOf).toList()))
                .param("kind", c.kind()).param("paid", c.paid()).param("charge", c.charge()).param("refund", c.refund())
                .param("breakdown", c.breakdownJson()).param("by", c.requestedBy())
                .update();
    }
}
