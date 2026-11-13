package in.railops.reservation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.NewBooking;
import in.railops.reservation.adapter.out.persistence.BookingRepository.NewPassenger;
import in.railops.reservation.adapter.out.persistence.IdempotencyRepository;
import in.railops.reservation.adapter.out.persistence.RunRepository;
import in.railops.reservation.adapter.out.persistence.RunRepository.RunView;
import in.railops.reservation.adapter.out.persistence.RunRepository.StopView;
import in.railops.reservation.application.AllocationEngine.Assignment;
import in.railops.reservation.application.AllocationEngine.Ctx;
import in.railops.reservation.application.AllocationEngine.Free;
import in.railops.reservation.application.BookingView.PassengerView;
import in.railops.reservation.application.fare.FarePort;
import in.railops.reservation.application.fare.FareQuote;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.BookingInitiated;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.policy.AllocationPlanner;
import in.railops.reservation.domain.policy.AllocationPlanner.Plan;
import in.railops.reservation.domain.policy.PnrGenerator;
import in.railops.reservation.domain.policy.QuotaEligibilityPolicy;
import in.railops.reservation.security.ContactProtector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    /** Signals "shortage, but expired holds exist in this pool": roll back, clean up, retry once. */
    private static final class CleanupRequired extends RuntimeException {
        CleanupRequired() {
            super(null, null, false, false);
        }
    }

    private static final int PNR_ATTEMPTS = 5;

    private final RunRepository runs;
    private final BookingRepository bookings;
    private final IdempotencyRepository idempotency;
    private final AllocationEngine engine;
    private final HoldReleaser holdReleaser;
    private final FarePort fares;
    private final OutboxWriter outbox;
    private final PoolGate poolGate;
    private final TransactionRunner tx;
    private final ContactProtector contacts;
    private final ObjectMapper mapper;
    private final ObjectMapper canonical;
    private final AllocationPlanner planner = new AllocationPlanner();
    private final QuotaEligibilityPolicy eligibility;
    private final PnrGenerator pnrs;
    private final int holdMinutes;
    private final WaitingRoomService waitingRoom;

    public BookingService(PoolGate poolGate, WaitingRoomService waitingRoom, RunRepository runs, BookingRepository bookings, IdempotencyRepository idempotency,
                          AllocationEngine engine, HoldReleaser holdReleaser, FarePort fares, OutboxWriter outbox,
                          TransactionRunner tx, ContactProtector contacts, ObjectMapper mapper,
                          QuotaEligibilityPolicy eligibility, PnrGenerator pnrs,
                          @Value("${railops.booking.hold-minutes:10}") int holdMinutes) {
        this.runs = runs;
        this.bookings = bookings;
        this.idempotency = idempotency;
        this.engine = engine;
        this.holdReleaser = holdReleaser;
        this.fares = fares;
        this.outbox = outbox;
        this.poolGate = poolGate;
        this.tx = tx;
        this.contacts = contacts;
        this.mapper = mapper;
        this.eligibility = eligibility;
        this.pnrs = pnrs;
        this.holdMinutes = holdMinutes;
        this.waitingRoom = waitingRoom;
        // Canonical form for request hashing: field order must not change the hash.
        this.canonical = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    public BookingView create(UUID userId, String idempotencyKey, BookingCommand cmd) {
        eligibility.check(cmd.quota(), cmd.passengers());
        waitingRoom.requireAdmission(cmd.trainRunId(), userId, cmd.quota());
        RunView run = runs.find(cmd.trainRunId())
                .orElseThrow(() -> new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "train run not found"));
        int distance = journeyDistance(cmd);

        // Remote call happens BEFORE the transaction: never hold row locks across the network.
        FareQuote quote = fares.quote(run.trainNo(), cmd.travelClass(), cmd.quota(), distance);
        byte[] hash = sha256(cmd);

        // Queue for the pool here, not on its row locks in PostgreSQL (see PoolGate for the measurement).
        return poolGate.through(cmd.trainRunId(), cmd.travelClass().code, cmd.quota().name(), () -> {
            try {
                return tx.inNewTransaction(() -> attempt(userId, idempotencyKey, cmd, hash, quote, true));
            } catch (CleanupRequired e) {
                holdReleaser.releaseExpiredInPool(cmd.trainRunId(), cmd.travelClass().code, cmd.quota().name());
                return tx.inNewTransaction(() -> attempt(userId, idempotencyKey, cmd, hash, quote, false));
            }
        });
    }

    @Transactional(readOnly = true)
    public BookingView get(UUID userId, String pnr) {
        if (!PnrGenerator.isValid(pnr)) {
            throw new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found");
        }
        BookingRow b = bookings.findByPnr(pnr)
                // Same error for "doesn't exist" and "not yours": no PNR existence oracle.
                .filter(row -> row.userId().equals(userId))
                .orElseThrow(() -> new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found"));
        List<PassengerView> pax = bookings.passengers(b.runDate(), b.id()).stream()
                .map(p -> new PassengerView(p.seqNo(), p.name(), p.age(), p.gender(), p.bookingStatusText(),
                        p.currentStatusText()))
                .toList();
        return new BookingView(b.pnr(), b.status().name(), b.trainRunId(), b.runDate(), b.classCode(),
                b.quotaCode(), b.fromSeq(), b.toSeq(), b.totalFare(), b.holdExpiresAt(), pax);
    }

    public record BookingSummary(String pnr, String status, long trainRunId, java.time.LocalDate runDate,
                                 String classCode, String quotaCode, int fromSeq, int toSeq, BigDecimal totalFare,
                                 Instant createdAt) {
    }

    public record BookingPage(List<BookingSummary> items, String nextCursor) {
    }

    /**
     * The caller's bookings, newest first, keyset-paginated on (created_at, id). OFFSET pagination
     * re-reads every skipped row and shifts under concurrent inserts; a keyset cursor does neither.
     * The cursor is opaque base64 so clients don't build it themselves.
     */
    @Transactional(readOnly = true)
    public BookingPage list(UUID userId, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Instant afterTs = null;
        long afterId = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8).split("\\|");
                afterTs = Instant.parse(p[0]);
                afterId = Long.parseLong(p[1]);
            } catch (RuntimeException e) {
                throw new DomainException(ErrorCode.RES_INVALID_JOURNEY, "invalid cursor");
            }
        }
        List<BookingSummary> rows = bookings.listForUser(userId, afterTs, afterId, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            BookingSummary last = rows.getLast();
            long lastId = bookings.idOf(last.pnr());
            next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.createdAt() + "|" + lastId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new BookingPage(rows, next);
    }

    private BookingView attempt(UUID userId, String key, BookingCommand cmd, byte[] hash, FareQuote quote,
                                boolean cleanupAllowed) {
        if (!idempotency.claim(userId, key, hash)) {
            return replay(userId, key, hash);
        }

        RunView run = runs.find(cmd.trainRunId()).orElseThrow();
        requireBookable(run, cmd);

        // ---- Everything that doesn't depend on inventory happens BEFORE the pool lock. The lock serialises every
        // booking in the pool, so each millisecond spent holding it caps that pool's throughput (Phase 10 measured
        // the hot pool as lock-bound). IDs and the PNR are wasted on rollback, which is harmless.
        long bookingId = bookings.nextIds("booking_id_seq", 1).getFirst();
        List<Long> pids = bookings.nextIds("passenger_id_seq", cmd.passengers().size());
        Map<Integer, Long> pidBySeq = new HashMap<>();
        for (int i = 0; i < cmd.passengers().size(); i++) {
            pidBySeq.put(cmd.passengers().get(i).seqNo(), pids.get(i));
        }
        String pnr = claimPnr(run, bookingId);
        long payingPassengers = cmd.passengers().stream().filter(PassengerSpec::berthRequired).count();
        BigDecimal total = quote.perPassenger().multiply(BigDecimal.valueOf(payingPassengers));
        String fareJson = json(Map.of("perPassenger", quote.components(), "payingPassengers", payingPassengers));
        byte[] phoneEnc = contacts.encryptPhone(cmd.contactPhone());
        byte[] emailEnc = contacts.encryptEmail(cmd.contactEmail());
        byte[] phoneToken = contacts.phoneLookupToken(cmd.contactPhone());

        // ---- Locked section: measure, plan, allocate, write. Keep it lean.
        Ctx ctx = new Ctx(run.id(), run.runDate(), cmd.travelClass(), cmd.quota(),
                new SegmentRange(cmd.fromSeq(), cmd.toSeq()));
        Free free = engine.lockAndMeasure(ctx);
        Plan plan = planner.plan(cmd.passengers(), free.cnf(), free.racSlots(), cmd.onlyIfConfirmed());
        if (plan.hasShortage() && cleanupAllowed
                && bookings.hasExpiredHoldsInPool(run.id(), cmd.travelClass().code, cmd.quota().name())) {
            throw new CleanupRequired();
        }

        List<Assignment> assigned = engine.execute(ctx, plan, free, pidBySeq, true, "PENDING");
        if (cmd.onlyIfConfirmed() && assigned.stream()
                .anyMatch(a -> a.status() != PassengerStatus.CNF && a.status() != PassengerStatus.NOSB)) {
            // Fragmentation downgraded someone after planning; the user asked for all-or-nothing.
            throw new DomainException(ErrorCode.RES_ONLY_IF_CNF_UNMET, "confirmed berths not available for everyone");
        }

        Instant holdExpiresAt = bookings.insert(new NewBooking(run.runDate(), bookingId, pnr, run.id(), userId,
                cmd.fromSeq(), cmd.toSeq(), cmd.travelClass().code, cmd.quota().name(), total, fareJson,
                phoneEnc, emailEnc, phoneToken, holdMinutes));
        for (Assignment a : assigned) {
            PassengerSpec p = a.spec();
            bookings.insertPassenger(new NewPassenger(run.runDate(), a.passengerId(), bookingId, p.seqNo(), p.name(),
                    p.age(), p.gender(), p.berthPref(), p.berthRequired(), a.status(), a.statusText()));
        }

        outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", pnr, "BookingInitiated",
                new BookingInitiated(pnr, run.id(), run.runDate(), cmd.travelClass().code, cmd.quota().name(),
                        cmd.fromSeq(), cmd.toSeq(), total, cmd.passengers().size(), holdExpiresAt), Map.of());
        outbox.append(ReservationEvents.TOPIC_INVENTORY, "TrainRun", Long.toString(run.id()), "InventoryChanged",
                new InventoryChanged(run.id(), cmd.travelClass().code, cmd.quota().name(), cmd.fromSeq(),
                        cmd.toSeq(), "BOOKED"), Map.of());

        BookingView view = new BookingView(pnr, "PENDING_PAYMENT", run.id(), run.runDate(), cmd.travelClass().code,
                cmd.quota().name(), cmd.fromSeq(), cmd.toSeq(), total, holdExpiresAt,
                assigned.stream().map(a -> new PassengerView(a.spec().seqNo(), a.spec().name(), a.spec().age(),
                        a.spec().gender(), a.statusText(), a.statusText())).toList());
        idempotency.complete(userId, key, 201, json(view));
        return view;
    }

    private BookingView replay(UUID userId, String key, byte[] hash) {
        IdempotencyRepository.Stored stored = idempotency.find(userId, key)
                .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
        if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
            throw new DomainException(ErrorCode.IDEM_KEY_REUSED, "Idempotency-Key was used with a different request");
        }
        if (!"COMPLETED".equals(stored.status())) {
            throw new DomainException(ErrorCode.IDEM_IN_PROGRESS, "request with this Idempotency-Key is in progress");
        }
        try {
            return mapper.readValue(stored.responseBody(), BookingView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response unreadable", e);
        }
    }

    private void requireBookable(RunView run, BookingCommand cmd) {
        if (!"SCHEDULED".equals(run.status())) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE, "run is " + run.status());
        }
        boolean open = cmd.quota().isTatkal() ? run.tatkalOpen() : run.generalOpen();
        if (!open) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE, "booking window not open for this quota");
        }
    }

    private int journeyDistance(BookingCommand cmd) {
        List<StopView> stops = runs.journeyStops(cmd.trainRunId(), cmd.fromSeq(), cmd.toSeq());
        if (cmd.toSeq() <= cmd.fromSeq() || stops.size() != 2 || !stops.get(0).halt() || !stops.get(1).halt()) {
            throw new DomainException(ErrorCode.RES_INVALID_JOURNEY, "boarding and destination must be halt stops");
        }
        if (stops.get(0).departed()) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE, "train has left the boarding station");
        }
        return stops.get(1).distanceKm() - stops.get(0).distanceKm();
    }

    private String claimPnr(RunView run, long bookingId) {
        for (int i = 0; i < PNR_ATTEMPTS; i++) {
            String candidate = pnrs.next();
            if (bookings.claimPnr(candidate, run.runDate(), bookingId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not allocate a unique PNR in " + PNR_ATTEMPTS + " attempts");
    }

    private byte[] sha256(BookingCommand cmd) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(cmd));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
