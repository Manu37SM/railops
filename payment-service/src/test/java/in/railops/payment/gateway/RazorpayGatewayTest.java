package in.railops.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Phase 6: the Razorpay adapter against recorded API shapes (no network). MONEY: paise, refunds, signatures. */
class RazorpayGatewayTest {

    static final String SECRET = "whsec-test-0123456789abcdef";
    final ObjectMapper json = new ObjectMapper();
    MockRestServiceServer server;
    RazorpayGateway gw;

    RazorpayGateway gateway(String keyId) {
        RestClient.Builder b = RestClient.builder();
        server = MockRestServiceServer.bindTo(b).build();
        return new RazorpayGateway(b, json, "https://api.razorpay.test/v1", keyId, "secret", SECRET,
                "https://raillens.example/bookings/{pnr}", true);
    }

    @BeforeEach
    void setUp() {
        gw = gateway("rzp_test_abc");
    }

    static void ok(MockRestServiceServer s, String url, HttpMethod m, String body) {
        s.expect(requestTo(url)).andExpect(method(m)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void refuses_live_keys_real_money_never_moves() {
        assertThatThrownBy(() -> gateway("rzp_live_abc")).isInstanceOf(IllegalStateException.class).hasMessageContaining("TEST");
    }

    @Test
    void creates_a_payment_link_in_paise_with_our_reference_and_the_booking_page_as_return() {
        UUID pay = UUID.randomUUID();
        server.expect(requestTo("https://api.razorpay.test/v1/payment_links")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("rzp_test_abc:secret".getBytes())))
                .andExpect(jsonPath("$.amount").value(84550))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.accept_partial").value(false))
                .andExpect(jsonPath("$.reference_id").value(pay.toString()))
                .andExpect(jsonPath("$.callback_url").value("https://raillens.example/bookings/4123456789"))
                .andExpect(jsonPath("$.notify.sms").value(false))
                .andRespond(withSuccess("{\"id\":\"plink_Abc123\",\"amount\":84550,\"short_url\":\"https://rzp.io/i/xYz\",\"status\":\"created\"}",
                        MediaType.APPLICATION_JSON));
        var order = gw.createOrder(pay, new BigDecimal("845.50"), "INR", "4123456789");
        assertThat(order.orderId()).isEqualTo("plink_Abc123");
        assertThat(order.checkoutUrl()).isEqualTo("https://rzp.io/i/xYz");
        server.verify();
    }

    @Test
    void a_link_for_a_different_amount_is_never_shown() {
        ok(server, "https://api.razorpay.test/v1/payment_links", HttpMethod.POST,
                "{\"id\":\"plink_Abc123\",\"amount\":100,\"short_url\":\"https://rzp.io/i/xYz\"}");
        assertThatThrownBy(() -> gw.createOrder(UUID.randomUUID(), new BigDecimal("845.50"), "INR", "4123456789"))
                .hasMessageContaining("amount");
    }

    @Test
    void link_status_maps_to_captured_failed_or_open() {
        ok(server, "https://api.razorpay.test/v1/payment_links/plink_P1", HttpMethod.GET,
                "{\"id\":\"plink_P1\",\"status\":\"paid\",\"amount_paid\":84550,\"payments\":[{\"payment_id\":\"pay_F\",\"status\":\"failed\"},{\"payment_id\":\"pay_OK\",\"status\":\"captured\"}]}");
        var st = gw.fetchOrder("plink_P1");
        assertThat(st.status()).isEqualTo("captured");
        assertThat(st.capturedAmount()).isEqualByComparingTo("845.50");
        assertThat(st.gatewayPaymentId()).isEqualTo("pay_OK");

        server.reset();
        ok(server, "https://api.razorpay.test/v1/payment_links/plink_P2", HttpMethod.GET, "{\"id\":\"plink_P2\",\"status\":\"expired\"}");
        assertThat(gw.fetchOrder("plink_P2").status()).isEqualTo("failed");
        server.reset();
        ok(server, "https://api.razorpay.test/v1/payment_links/plink_P3", HttpMethod.GET, "{\"id\":\"plink_P3\",\"status\":\"created\"}");
        assertThat(gw.fetchOrder("plink_P3").status()).isEqualTo("created");
        assertThatThrownBy(() -> gw.fetchOrder("../admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_retried_refund_finds_the_first_one_instead_of_refunding_twice() {
        UUID refund = UUID.randomUUID();
        ok(server, "https://api.razorpay.test/v1/payment_links/plink_P1", HttpMethod.GET,
                "{\"id\":\"plink_P1\",\"status\":\"paid\",\"amount_paid\":84550,\"payments\":[{\"payment_id\":\"pay_OK\",\"status\":\"captured\"}]}");
        ok(server, "https://api.razorpay.test/v1/payments/pay_OK/refunds", HttpMethod.GET,
                "{\"items\":[{\"id\":\"rfnd_First\",\"receipt\":\"" + refund + "\"}]}");
        assertThat(gw.refund("plink_P1", refund, new BigDecimal("665.00")).gatewayRefundId()).isEqualTo("rfnd_First");
        server.verify();   // no POST happened
    }

    @Test
    void a_new_refund_is_sent_in_paise_with_our_id_as_receipt() {
        UUID refund = UUID.randomUUID();
        ok(server, "https://api.razorpay.test/v1/payment_links/plink_P1", HttpMethod.GET,
                "{\"id\":\"plink_P1\",\"status\":\"paid\",\"amount_paid\":84550,\"payments\":[{\"payment_id\":\"pay_OK\",\"status\":\"captured\"}]}");
        ok(server, "https://api.razorpay.test/v1/payments/pay_OK/refunds", HttpMethod.GET, "{\"items\":[]}");
        server.expect(requestTo("https://api.razorpay.test/v1/payments/pay_OK/refunds")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(66500)).andExpect(jsonPath("$.receipt").value(refund.toString()))
                .andRespond(withSuccess("{\"id\":\"rfnd_New\",\"status\":\"pending\"}", MediaType.APPLICATION_JSON));
        assertThat(gw.refund("plink_P1", refund, new BigDecimal("665.00")).gatewayRefundId()).isEqualTo("rfnd_New");
    }

    static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void webhook_signatures_are_checked_over_the_exact_bytes() throws Exception {
        byte[] body = "{\"event\":\"payment_link.paid\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(gw.verifySignature(sign(body), body)).isTrue();
        assertThat(gw.verifySignature(sign(body), "{\"event\": \"payment_link.paid\"}".getBytes())).as("re-serialised").isFalse();
        assertThat(gw.verifySignature(null, body)).isFalse();
        assertThat(gw.verifySignature("zz", body)).isFalse();
    }

    @Test
    void razorpay_events_become_internal_events() throws Exception {
        byte[] paid = """
                {"entity":"event","event":"payment_link.paid","payload":{
                 "payment_link":{"entity":{"id":"plink_P1","amount_paid":84550,"currency":"INR","status":"paid"}},
                 "payment":{"entity":{"id":"pay_OK","amount":84550,"currency":"INR","status":"captured"}}}}""".getBytes();
        JsonNode n = json.readTree(gw.normalize("evt_1", paid));
        assertThat(n.path("id").asText()).isEqualTo("rzp:evt_1");
        assertThat(n.path("type").asText()).isEqualTo("payment.captured");
        assertThat(n.path("data").path("orderId").asText()).isEqualTo("plink_P1");
        assertThat(n.path("data").path("paymentId").asText()).isEqualTo("pay_OK");
        assertThat(n.path("data").path("amount").asText()).isEqualTo("845.50");

        JsonNode exp = json.readTree(gw.normalize("evt_2", "{\"event\":\"payment_link.expired\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"plink_P2\"}}}}".getBytes()));
        assertThat(exp.path("type").asText()).isEqualTo("payment.failed");
        JsonNode rf = json.readTree(gw.normalize("evt_3", "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_New\"}}}}".getBytes()));
        assertThat(rf.path("data").path("refundId").asText()).isEqualTo("rfnd_New");
        JsonNode other = json.readTree(gw.normalize(null, "{\"event\":\"payment.failed\",\"payload\":{}}".getBytes()));
        assertThat(other.path("type").asText()).startsWith("ignored.");
        assertThat(other.path("id").asText()).startsWith("rzp:sha256:");
    }
}
