package in.railops.reservation.adapter.in.web;

import in.railops.reservation.adapter.out.http.PaymentClient.PaymentIntent;
import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.application.BookingService.BookingPage;
import in.railops.reservation.application.BookingView;
import in.railops.reservation.application.CancellationService;
import in.railops.reservation.application.CancellationService.CancellationView;
import in.railops.reservation.application.PaymentIntentService;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@Tag(name = "Bookings", description = "Passenger booking lifecycle: book, pay, view, cancel")
@SecurityRequirement(name = "bearer")
public class BookingController {

    private static final String IDEM = "^[A-Za-z0-9-]{16,64}$";
    static final String PNR = "^[1-9][0-9]{9}$";

    public record CancelRequest(@Size(max = 6) List<@Min(1) @Max(6) Integer> passengerSeqs) {
    }

    private final BookingService bookings;
    private final CancellationService cancellations;
    private final PaymentIntentService paymentIntents;
    private final CurrentUserProvider currentUser;
    private final in.railops.reservation.application.SimulatedPaymentService simulatedPayments;

    public BookingController(BookingService bookings, CancellationService cancellations,
                             PaymentIntentService paymentIntents, CurrentUserProvider currentUser,
                             in.railops.reservation.application.SimulatedPaymentService simulatedPayments) {
        this.simulatedPayments = simulatedPayments;
        this.bookings = bookings;
        this.cancellations = cancellations;
        this.paymentIntents = paymentIntents;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Create a booking and hold seats for 10 minutes",
            description = "Allocates CNF, then RAC, then WL per passenger. Retrying with the same Idempotency-Key "
                    + "returns the original booking; the same key with a different body is rejected (422 IDEM_KEY_REUSED).")
    @ApiResponse(responseCode = "201", description = "Booking created, PENDING_PAYMENT")
    @ApiResponse(responseCode = "409", description = "RES_REGRET, RES_ONLY_IF_CNF_UNMET, RES_RUN_NOT_BOOKABLE",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "RES_QUOTA_INELIGIBLE, RES_INVALID_JOURNEY, IDEM_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BookingView> create(
            @Parameter(description = "Client-generated, unique per logical booking attempt", required = true)
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEM) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest req) {
        BookingView view = bookings.create(currentUser.requireUserId(), idempotencyKey, toCommand(req));
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + view.pnr())).body(view);
    }

    @GetMapping
    @Operation(summary = "List my bookings, newest first",
            description = "Keyset pagination: pass nextCursor from the previous page as cursor. Cursors are opaque.")
    public BookingPage list(@RequestParam(required = false) @Size(max = 200) String cursor,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return bookings.list(currentUser.requireUserId(), cursor, limit);
    }

    @GetMapping("/{pnr}")
    @Operation(summary = "Get one of my bookings", description = "404 both when the PNR doesn't exist and when it isn't yours.")
    public BookingView get(@PathVariable @Pattern(regexp = PNR) String pnr) {
        return bookings.get(currentUser.requireUserId(), pnr);
    }

    @PostMapping("/{pnr}/payment-intent")
    @Operation(summary = "Create or fetch the payment intent for an unpaid booking",
            description = "The amount is always the server-side fare. Safe to call repeatedly: one live payment per PNR.")
    @ApiResponse(responseCode = "201", description = "Intent with a gateway checkout URL")
    @ApiResponse(responseCode = "410", description = "RES_HOLD_EXPIRED", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "PAYMENT_UNAVAILABLE (retry with backoff)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentIntent> paymentIntent(@PathVariable @Pattern(regexp = PNR) String pnr) {
        return ResponseEntity.status(201).body(paymentIntents.create(currentUser.requireUserId(), pnr));
    }

    public record SimulatedPaymentRequest(@jakarta.validation.constraints.NotNull
                                          @Pattern(regexp = "SUCCESS|FAILURE") String outcome) {
    }

    @PostMapping("/{pnr}/simulated-payment")
    @Operation(summary = "Finish the simulated checkout for my unpaid booking (demo only)",
            description = "Only where the server runs simulated payments; otherwise 404 RES_SIMULATED_PAYMENTS_OFF. "
                    + "SUCCESS or FAILURE. The booking changes status a few seconds later, as with a real gateway: poll GET /bookings/{pnr}. "
                    + "No money moves.")
    @ApiResponse(responseCode = "202", description = "Outcome sent to the simulated gateway")
    public ResponseEntity<PaymentIntent> simulatedPayment(@PathVariable @Pattern(regexp = PNR) String pnr,
                                                          @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid SimulatedPaymentRequest req) {
        return ResponseEntity.status(202).body(simulatedPayments.pay(currentUser.requireUserId(), pnr, "SUCCESS".equals(req.outcome())));
    }

    @GetMapping("/{pnr}/cancellation-quote")
    @Operation(summary = "Preview the refund for cancelling some or all passengers",
            description = "Omit passengers to quote the whole booking. The quote is valid for the current clock window only.")
    public CancellationView quote(@PathVariable @Pattern(regexp = PNR) String pnr,
                                  @RequestParam(name = "passengers", required = false) List<@Min(1) @Max(6) Integer> seqs) {
        return cancellations.quote(currentUser.requireUserId(), pnr, seqs);
    }

    @PostMapping("/{pnr}/cancellations")
    @Operation(summary = "Cancel some or all passengers",
            description = "Idempotent: the refund is requested at most once per Idempotency-Key. Unpaid bookings can only "
                    + "be cancelled whole. Children without a berth can't remain without an adult who has one.")
    @ApiResponse(responseCode = "200", description = "Cancellation applied; refund requested if any is due")
    @ApiResponse(responseCode = "409", description = "RES_CANCELLATION_CLOSED", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public CancellationView cancel(@RequestHeader("Idempotency-Key") @Pattern(regexp = IDEM) String idempotencyKey,
                                   @PathVariable @Pattern(regexp = PNR) String pnr,
                                   @Valid @RequestBody(required = false) CancelRequest req) {
        return cancellations.cancel(currentUser.requireUserId(), idempotencyKey, pnr,
                req == null ? null : req.passengerSeqs());
    }

    private static BookingCommand toCommand(CreateBookingRequest r) {
        List<PassengerSpec> pax = new ArrayList<>();
        for (int i = 0; i < r.passengers().size(); i++) {
            var p = r.passengers().get(i);
            boolean berth = p.berthRequired() == null || p.berthRequired();
            pax.add(new PassengerSpec(i + 1, p.name().trim(), p.age(), p.gender().charAt(0), p.berthPreference(), berth));
        }
        return new BookingCommand(r.trainRunId(), r.fromStopSeq(), r.toStopSeq(), r.travelClass(), r.quota(), pax,
                r.onlyIfConfirmed(), r.contactPhone(), r.contactEmail());
    }
}
