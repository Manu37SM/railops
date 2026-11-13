package in.railops.payment.it;

import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Phase 6: a signed Razorpay webhook captures the payment once, on real PostgreSQL, with the "razorpay" profile. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("razorpay")
class RazorpayWebhookIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();
    static final String SECRET = "whsec-test-0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.scheduling.enabled", () -> "false");
        r.add("spring.kafka.listener.auto-startup", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        r.add("railops.kafka.replicas", () -> "1");
        r.add("railops.gateway.webhook-secret", () -> "unused-in-razorpay-mode-0123456789abcdef");
        r.add("railops.razorpay.base-url", () -> "http://127.0.0.1:1/v1");   // never called here
        r.add("railops.razorpay.key-id", () -> "rzp_test_it");
        r.add("railops.razorpay.key-secret", () -> "secret");
        r.add("railops.razorpay.webhook-secret", () -> SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    String pendingLink(BigDecimal amount) {
        String link = "plink_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String pnr = "5" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        jdbc.sql("""
                INSERT INTO payment.payment (id, pnr, user_id, amount, status, gateway, gateway_order_id, pay_by)
                VALUES (:id, :pnr, :u, :a, 'PENDING', 'razorpay', :o, now() + interval '10 minutes')
                """).param("id", UUID.randomUUID()).param("pnr", pnr).param("u", UUID.randomUUID()).param("a", amount)
                .param("o", link).update();
        return link;
    }

    static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    int send(byte[] body, String sig, String eventId) throws Exception {
        return mvc.perform(post("/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id", eventId)).andReturn().getResponse().getStatus();
    }

    String status(String link) {
        return jdbc.sql("SELECT status || ':' || captured_amount FROM payment.payment WHERE gateway_order_id = :o")
                .param("o", link).query(String.class).single();
    }

    byte[] paid(String link) {
        return ("{\"entity\":\"event\",\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + link
                + "\",\"amount_paid\":84550,\"currency\":\"INR\",\"status\":\"paid\"}},\"payment\":{\"entity\":{\"id\":\"pay_"
                + UUID.randomUUID().toString().substring(0, 8) + "\",\"amount\":84550,\"currency\":\"INR\",\"status\":\"captured\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void a_signed_paid_event_captures_the_payment_and_emits_the_event_exactly_once() throws Exception {
        String link = pendingLink(new BigDecimal("845.50"));
        byte[] body = paid(link);
        assertThat(send(body, sign(body), "evt_" + link)).isEqualTo(200);
        assertThat(status(link)).isEqualTo("CAPTURED:845.50");
        assertThat(send(body, sign(body), "evt_" + link)).as("redelivery").isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentCaptured' AND payload::text LIKE :p")
                .param("p", "%" + jdbc.sql("SELECT pnr FROM payment.payment WHERE gateway_order_id = :o").param("o", link)
                        .query(String.class).single() + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void a_forged_webhook_changes_nothing() throws Exception {
        String link = pendingLink(new BigDecimal("845.50"));
        byte[] body = paid(link);
        assertThat(send(body, sign("{}".getBytes()), "evt_x" + link)).isEqualTo(401);
        assertThat(send(body, "", "evt_y" + link)).isEqualTo(401);
        assertThat(status(link)).isEqualTo("PENDING:0.00");
    }

    @Test
    void an_expired_link_fails_the_payment() throws Exception {
        String link = pendingLink(new BigDecimal("100.00"));
        byte[] body = ("{\"event\":\"payment_link.expired\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + link + "\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(send(body, sign(body), "evt_e" + link)).isEqualTo(200);
        assertThat(status(link)).startsWith("FAILED");
    }
}
