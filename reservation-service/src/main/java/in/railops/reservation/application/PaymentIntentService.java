package in.railops.reservation.application;

import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.adapter.out.http.PaymentClient.CreatePayment;
import in.railops.reservation.adapter.out.http.PaymentClient.PaymentIntent;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.policy.PnrGenerator;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Creates (or returns the existing) payment intent for an unpaid booking.
 *
 * <p>Three steps, deliberately not one transaction: read and validate (short TX) → remote call (no TX, no
 * row locks held across the network) → attach payment id (short TX). If the hold lapses in between,
 * nothing breaks: the late-payment path of PaymentConfirmationService re-seats or refunds.
 */
@Service
public class PaymentIntentService {

    private final BookingRepository bookings;
    private final PaymentClient payments;
    private final TransactionRunner tx;

    public PaymentIntentService(BookingRepository bookings, PaymentClient payments, TransactionRunner tx) {
        this.bookings = bookings;
        this.payments = payments;
        this.tx = tx;
    }

    public PaymentIntent create(UUID userId, String pnr) {
        BookingRow b = tx.inNewTransaction(() -> {
            if (!PnrGenerator.isValid(pnr)) {
                throw new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found");
            }
            BookingRow row = bookings.findByPnr(pnr).filter(r -> r.userId().equals(userId))
                    .orElseThrow(() -> new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found"));
            if (row.status() != BookingStatus.PENDING_PAYMENT) {
                throw new DomainException(ErrorCode.RES_BOOKING_NOT_PAYABLE, "booking is " + row.status());
            }
            if (!row.holdValid()) {
                throw new DomainException(ErrorCode.RES_HOLD_EXPIRED, "seat hold expired; please book again");
            }
            return row;
        });

        PaymentIntent intent = payments.create(new CreatePayment(b.pnr(), userId, b.totalFare(), "INR", b.holdExpiresAt()));

        tx.inNewTransaction(() -> bookings.lockByPnr(pnr).ifPresent(locked -> {
            if (locked.paymentId() == null) {
                bookings.attachPayment(locked, intent.paymentId());
            } else if (!locked.paymentId().equals(intent.paymentId())) {
                // payment-service is idempotent per PNR, so this means a new intent after a failed one.
                // PaymentConfirmationService refunds captures for any payment id other than the bound one,
                // so rebinding must only happen while unpaid, which the status check below guarantees.
                if (locked.status() == BookingStatus.PENDING_PAYMENT) {
                    bookings.rebindPayment(locked, intent.paymentId());
                }
            }
        }));
        return intent;
    }
}
