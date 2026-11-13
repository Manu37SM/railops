package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class BookingRepository {

    public record BookingRow(LocalDate runDate, long id, String pnr, long trainRunId, UUID userId,
                             int fromSeq, int toSeq, String classCode, String quotaCode, BookingStatus status,
                             BigDecimal totalFare, UUID paymentId, Instant holdExpiresAt, boolean holdValid) {
    }

    public record PassengerRow(long id, int seqNo, String name, int age, char gender, String berthPref,
                               boolean berthRequired, PassengerStatus status, String bookingStatusText,
                               String currentStatusText) {
    }

    public record NewBooking(LocalDate runDate, long id, String pnr, long trainRunId, UUID userId, int fromSeq,
                             int toSeq, String cls, String quota, BigDecimal totalFare, String fareBreakupJson,
                             byte[] phoneEnc, byte[] emailEnc, byte[] phoneHmac, int holdMinutes) {
    }

    public record NewPassenger(LocalDate runDate, long id, long bookingId, int seqNo, String name, int age, char gender,
                               String berthPref, boolean berthRequired, PassengerStatus status, String statusText) {
    }

    private static final String BOOKING_COLS = """
            b.run_date, b.id, b.pnr, b.train_run_id, b.user_id, b.from_seq, b.to_seq, b.class_code, b.quota_code,
            b.status, b.total_fare, b.payment_id, b.hold_expires_at,
            COALESCE(b.hold_expires_at > now(), false) AS hold_valid
            """;

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> nextIds(String sequence, int count) {
        if (!sequence.matches("[a-z_]+")) {
            throw new IllegalArgumentException("bad sequence name");
        }
        return jdbc.sql("SELECT nextval('reservation." + sequence + "') FROM generate_series(1, :n)")
                .param("n", count)
                .query(Long.class)
                .list();
    }

    /** False on PNR collision; the caller generates another. */
    public boolean claimPnr(String pnr, LocalDate runDate, long bookingId) {
        return jdbc.sql("INSERT INTO reservation.pnr_directory (pnr, run_date, booking_id) VALUES (:p, :d, :b) "
                        + "ON CONFLICT DO NOTHING")
                .param("p", pnr).param("d", runDate).param("b", bookingId)
                .update() == 1;
    }

    /** Returns the hold expiry the database assigned, so the API reports the DB clock, not the JVM's. */
    public Instant insert(NewBooking b) {
        return jdbc.sql("""
                        INSERT INTO reservation.booking
                          (run_date, id, pnr, train_run_id, user_id, from_seq, to_seq, class_code, quota_code, status,
                           total_fare, fare_breakup, contact_phone_enc, contact_email_enc, contact_phone_hmac,
                           hold_expires_at)
                        VALUES (:runDate, :id, :pnr, :run, :user, :from, :to, :cls, :quota, 'PENDING_PAYMENT',
                                :fare, CAST(:breakup AS jsonb), :phoneEnc, :emailEnc, :phoneHmac,
                                now() + make_interval(mins => :holdMins))
                        RETURNING hold_expires_at
                        """)
                .param("runDate", b.runDate()).param("id", b.id()).param("pnr", b.pnr()).param("run", b.trainRunId())
                .param("user", b.userId()).param("from", b.fromSeq()).param("to", b.toSeq())
                .param("cls", b.cls()).param("quota", b.quota()).param("fare", b.totalFare())
                .param("breakup", b.fareBreakupJson()).param("phoneEnc", b.phoneEnc()).param("emailEnc", b.emailEnc())
                .param("phoneHmac", b.phoneHmac()).param("holdMins", b.holdMinutes())
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    public void insertPassenger(NewPassenger p) {
        jdbc.sql("""
                        INSERT INTO reservation.passenger
                          (run_date, id, booking_id, seq_no, name, age, gender, berth_pref, berth_required,
                           status, booking_status_text, current_status_text)
                        VALUES (:runDate, :id, :booking, :seq, :name, :age, :gender, :pref, :required,
                                :status, :text, :text)
                        """)
                .param("runDate", p.runDate()).param("id", p.id()).param("booking", p.bookingId())
                .param("seq", p.seqNo()).param("name", p.name()).param("age", p.age())
                .param("gender", String.valueOf(p.gender())).param("pref", p.berthPref())
                .param("required", p.berthRequired()).param("status", p.status().name()).param("text", p.statusText())
                .update();
    }

    public Optional<BookingRow> lockByPnr(String pnr) {
        return jdbc.sql("SELECT " + BOOKING_COLS + """
                        FROM reservation.booking b
                        JOIN reservation.pnr_directory d ON d.run_date = b.run_date AND d.booking_id = b.id
                        WHERE d.pnr = :pnr
                        FOR UPDATE OF b
                        """)
                .param("pnr", pnr)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    public Optional<BookingRow> findByPnr(String pnr) {
        return jdbc.sql("SELECT " + BOOKING_COLS + """
                        FROM reservation.booking b
                        JOIN reservation.pnr_directory d ON d.run_date = b.run_date AND d.booking_id = b.id
                        WHERE d.pnr = :pnr
                        """)
                .param("pnr", pnr)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    /** Claims one expired pending booking, or nothing if another worker holds it or it was already handled. */
    public Optional<BookingRow> lockIfExpiredPending(LocalDate runDate, long id) {
        return jdbc.sql("SELECT " + BOOKING_COLS + """
                        FROM reservation.booking b
                        WHERE b.run_date = :runDate AND b.id = :id
                          AND b.status = 'PENDING_PAYMENT' AND b.hold_expires_at < now()
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("runDate", runDate).param("id", id)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    public record BookingKey(LocalDate runDate, long id) {
    }

    public record ExpiredHold(BookingKey key, String pnr, java.util.UUID paymentId, java.time.Instant holdExpiresAt) {
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)   // the class default is MANDATORY (INC-001b)
    public List<ExpiredHold> expiredHolds(int limit) {
        return jdbc.sql("""
                        SELECT run_date, id, pnr, payment_id, hold_expires_at FROM reservation.booking
                        WHERE status = 'PENDING_PAYMENT' AND hold_expires_at < now()
                        ORDER BY hold_expires_at LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new ExpiredHold(new BookingKey(rs.getObject("run_date", LocalDate.class), rs.getLong("id")),
                        rs.getString("pnr"), rs.getObject("payment_id", java.util.UUID.class),
                        rs.getTimestamp("hold_expires_at").toInstant()))
                .list();
    }

    public Optional<BookingRow> lockById(LocalDate runDate, long id) {
        return jdbc.sql("SELECT " + BOOKING_COLS + " FROM reservation.booking b WHERE b.run_date = :d AND b.id = :id FOR UPDATE")
                .param("d", runDate).param("id", id)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    /** Chart time: claim an unpaid booking whether or not its hold has expired yet. */
    public Optional<BookingRow> lockIfPendingOnRun(LocalDate runDate, long id) {
        return jdbc.sql("SELECT " + BOOKING_COLS + """
                        FROM reservation.booking b
                        WHERE b.run_date = :d AND b.id = :id AND b.status = 'PENDING_PAYMENT'
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("d", runDate).param("id", id)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<BookingKey> expiredPendingKeys(int limit) {
        return jdbc.sql("""
                        SELECT run_date, id FROM reservation.booking
                        WHERE status = 'PENDING_PAYMENT' AND hold_expires_at < now()
                        ORDER BY hold_expires_at LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new BookingKey(rs.getObject("run_date", LocalDate.class), rs.getLong("id")))
                .list();
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<BookingKey> expiredPendingKeysInPool(long runId, String cls, String quota, int limit) {
        return jdbc.sql("""
                        SELECT run_date, id FROM reservation.booking
                        WHERE status = 'PENDING_PAYMENT' AND hold_expires_at < now()
                          AND train_run_id = :run AND class_code = :cls AND quota_code = :quota
                          -- holds with a payment in flight are left to HoldSweeper, which asks payment-service
                          -- first (INC-001); the booking path must not make that HTTP call inside a user request
                          AND payment_id IS NULL
                        ORDER BY hold_expires_at LIMIT :limit
                        """)
                .param("run", runId).param("cls", cls).param("quota", quota).param("limit", limit)
                .query((rs, n) -> new BookingKey(rs.getObject("run_date", LocalDate.class), rs.getLong("id")))
                .list();
    }

    public boolean hasExpiredHoldsInPool(long runId, String cls, String quota) {
        return !expiredPendingKeysInPool(runId, cls, quota, 1).isEmpty();
    }

    public void updateStatus(BookingRow b, BookingStatus next) {
        b.status().requireTransition(next);
        int n = jdbc.sql("""
                        UPDATE reservation.booking
                        SET status = :next, updated_at = now(), version = version + 1,
                            hold_expires_at = CASE WHEN :next = 'PENDING_PAYMENT' THEN hold_expires_at END
                        WHERE run_date = :runDate AND id = :id AND status = :current
                        """)
                .param("next", next.name()).param("runDate", b.runDate()).param("id", b.id())
                .param("current", b.status().name())
                .update();
        if (n != 1) {
            throw new IllegalStateException("booking " + b.pnr() + " changed underneath a held lock");
        }
    }

    public void attachPayment(BookingRow b, UUID paymentId) {
        jdbc.sql("UPDATE reservation.booking SET payment_id = :p WHERE run_date = :d AND id = :id AND payment_id IS NULL")
                .param("p", paymentId).param("d", b.runDate()).param("id", b.id())
                .update();
    }

    /** Records that this payment's capture has been consumed (confirmed, re-seated or refunded). */
    public void markCaptureApplied(BookingRow b, UUID paymentId) {
        jdbc.sql("UPDATE reservation.booking SET captured_payment_id = :p WHERE run_date = :d AND id = :id")
                .param("p", paymentId).param("d", b.runDate()).param("id", b.id()).update();
    }

    /** Caller holds the row lock. */
    public boolean captureApplied(BookingRow b, UUID paymentId) {
        return jdbc.sql("SELECT captured_payment_id = :p FROM reservation.booking WHERE run_date = :d AND id = :id")
                .param("p", paymentId).param("d", b.runDate()).param("id", b.id())
                .query(Boolean.class).optional().map(Boolean.TRUE::equals).orElse(false);
    }

    /** Only while unpaid: a superseded intent (first attempt failed) is replaced by the new one. */
    public void rebindPayment(BookingRow b, UUID paymentId) {
        jdbc.sql("""
                        UPDATE reservation.booking SET payment_id = :p
                        WHERE run_date = :d AND id = :id AND status = 'PENDING_PAYMENT'
                        """)
                .param("p", paymentId).param("d", b.runDate()).param("id", b.id())
                .update();
    }

    public List<PassengerRow> passengers(LocalDate runDate, long bookingId) {
        return jdbc.sql("""
                        SELECT id, seq_no, name, age, gender, berth_pref, berth_required, status,
                               booking_status_text, current_status_text
                        FROM reservation.passenger WHERE run_date = :runDate AND booking_id = :booking
                        ORDER BY seq_no
                        """)
                .param("runDate", runDate).param("booking", bookingId)
                .query((rs, n) -> new PassengerRow(rs.getLong("id"), rs.getInt("seq_no"), rs.getString("name"),
                        rs.getInt("age"), rs.getString("gender").charAt(0), rs.getString("berth_pref"),
                        rs.getBoolean("berth_required"), PassengerStatus.valueOf(rs.getString("status")),
                        rs.getString("booking_status_text"), rs.getString("current_status_text")))
                .list();
    }

    public void updatePassengerStatus(LocalDate runDate, long passengerId, PassengerStatus status, String text) {
        jdbc.sql("""
                        UPDATE reservation.passenger SET status = :s, current_status_text = :t
                        WHERE run_date = :runDate AND id = :id
                        """)
                .param("s", status.name()).param("t", text).param("runDate", runDate).param("id", passengerId)
                .update();
    }

    public Optional<PassengerRow> passengerById(LocalDate runDate, long passengerId) {
        return jdbc.sql("""
                        SELECT id, seq_no, name, age, gender, berth_pref, berth_required, status,
                               booking_status_text, current_status_text
                        FROM reservation.passenger WHERE run_date = :runDate AND id = :id
                        """)
                .param("runDate", runDate).param("id", passengerId)
                .query((rs, n) -> new PassengerRow(rs.getLong("id"), rs.getInt("seq_no"), rs.getString("name"),
                        rs.getInt("age"), rs.getString("gender").charAt(0), rs.getString("berth_pref"),
                        rs.getBoolean("berth_required"), PassengerStatus.valueOf(rs.getString("status")),
                        rs.getString("booking_status_text"), rs.getString("current_status_text")))
                .optional();
    }

    /** Pnr of the booking owning a passenger; used to address promotion notifications. */
    public Optional<String> pnrOfPassenger(LocalDate runDate, long passengerId) {
        return jdbc.sql("""
                        SELECT b.pnr FROM reservation.passenger p
                        JOIN reservation.booking b ON b.run_date = p.run_date AND b.id = p.booking_id
                        WHERE p.run_date = :runDate AND p.id = :id
                        """)
                .param("runDate", runDate).param("id", passengerId)
                .query(String.class)
                .optional();
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<in.railops.reservation.application.BookingService.BookingSummary> listForUser(
            UUID userId, Instant afterTs, long afterId, int limit) {
        return jdbc.sql("""
                        SELECT pnr, status, train_run_id, run_date, class_code, quota_code, from_seq, to_seq,
                               total_fare, created_at
                        FROM reservation.booking
                        WHERE user_id = :user
                          AND (CAST(:afterTs AS timestamptz) IS NULL OR (created_at, id) < (CAST(:afterTs AS timestamptz), :afterId))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("user", userId)
                .param("afterTs", afterTs == null ? null : java.sql.Timestamp.from(afterTs))
                .param("afterId", afterId).param("limit", limit)
                .query((rs, n) -> new in.railops.reservation.application.BookingService.BookingSummary(
                        rs.getString("pnr"), rs.getString("status"), rs.getLong("train_run_id"),
                        rs.getObject("run_date", LocalDate.class), rs.getString("class_code"),
                        rs.getString("quota_code"), rs.getInt("from_seq"), rs.getInt("to_seq"),
                        rs.getBigDecimal("total_fare"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public long idOf(String pnr) {
        return jdbc.sql("SELECT booking_id FROM reservation.pnr_directory WHERE pnr = :p").param("p", pnr)
                .query(Long.class).single();
    }

    private static BookingRow mapBooking(ResultSet rs, int n) throws SQLException {
        OffsetDateTime hold = rs.getObject("hold_expires_at", OffsetDateTime.class);
        return new BookingRow(rs.getObject("run_date", LocalDate.class), rs.getLong("id"), rs.getString("pnr"),
                rs.getLong("train_run_id"), rs.getObject("user_id", UUID.class), rs.getInt("from_seq"),
                rs.getInt("to_seq"), rs.getString("class_code"), rs.getString("quota_code"),
                BookingStatus.valueOf(rs.getString("status")), rs.getBigDecimal("total_fare"),
                rs.getObject("payment_id", UUID.class), hold == null ? null : hold.toInstant(),
                rs.getBoolean("hold_valid"));
    }
}
