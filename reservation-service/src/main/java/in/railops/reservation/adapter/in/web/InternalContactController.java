package in.railops.reservation.adapter.in.web;

import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.security.ContactProtector;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Contact details for notifications, for services holding SCOPE_contacts:read (SecurityConfig).
 *
 * Why pull instead of putting the phone number in events: Kafka topics are retained, replicated, mirrored to
 * analytics and readable by every consumer group. PII in a payload spreads to all of them and can't be erased
 * on request. A pull endpoint keeps one authorised, logged access path, and "forget me" works.
 */
@RestController
public class InternalContactController {

    public record Contact(String pnr, String phone, String email) {
    }

    private final JdbcClient jdbc;
    private final ContactProtector contacts;

    public InternalContactController(JdbcClient jdbc, ContactProtector contacts) {
        this.jdbc = jdbc;
        this.contacts = contacts;
    }

    @GetMapping("/internal/v1/bookings/{pnr}/contact")
    public ResponseEntity<Contact> contact(@PathVariable String pnr, Authentication caller) {
        if (!pnr.matches("[1-9]\\d{9}")) {
            return ResponseEntity.notFound().build();
        }
        return jdbc.sql("""
                        SELECT b.contact_phone_enc, b.contact_email_enc FROM reservation.booking b
                        JOIN reservation.pnr_directory d ON d.run_date = b.run_date AND d.booking_id = b.id
                        WHERE d.pnr = :pnr
                        """).param("pnr", pnr)
                .query((rs, n) -> new Contact(pnr, contacts.decryptPhone(rs.getBytes(1)), contacts.decryptEmail(rs.getBytes(2))))
                .optional()
                .map(c -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(c))
                .orElse(ResponseEntity.notFound().build());
    }
}
