package in.railops.reservation.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import in.railops.reservation.adapter.in.web.InternalContactController;
import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.adapter.out.http.ServiceTokenClient;
import in.railops.testsupport.HttpContract;
import in.railops.testsupport.ProductionObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** reservation as CONSUMER of payment-service, and as PROVIDER of the contact lookup to notification. */
class PaymentHttpConsumerContractTest {

    final ObjectMapper mapper = ProductionObjectMapper.load();
    final HttpContract contract = HttpContract.load("reservation-to-payment.create-payment");

    PaymentClient client(HttpServer server) {
        ServiceTokenClient tokens = mock(ServiceTokenClient.class);
        when(tokens.token()).thenReturn("svc-token");
        RestClient.Builder b = RestClient.builder()
                .messageConverters(c -> {
                    c.removeIf(x -> x instanceof MappingJackson2HttpMessageConverter);
                    // Deliberately LAST, behind the YAML converter: the client must not depend on converter order.
                    c.add(new MappingJackson2HttpMessageConverter(mapper));
                });
        return new PaymentClient(b, tokens, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    PaymentClient.CreatePayment request() {
        JsonNode r = contract.request().get("body");
        return new PaymentClient.CreatePayment(r.get("pnr").asText(), UUID.fromString(r.get("userId").asText()),
                new BigDecimal("2915.00"), "INR", Instant.parse(r.get("payBy").asText()));
    }

    @Test
    void sends_what_payment_service_expects_and_understands_its_answer() throws Exception {
        var rec = new HttpContract.Recorded();
        HttpServer server = contract.stubProvider(rec);
        try {
            PaymentClient.PaymentIntent intent = client(server).create(request());
            assertThat(rec.headers.get("content-type")).startsWith("application/json");

            assertThat(rec.method).isEqualTo(contract.request().get("method").asText());
            assertThat(rec.path).isEqualTo(contract.request().get("path").asText());
            assertThat(rec.headers.get("authorization")).isEqualTo("Bearer svc-token");
            assertThat(rec.headers.get("idempotency-key")).isEqualTo("pnr-2400000018");   // one intent per PNR
            HttpContract.assertSameShape(contract.request().get("body"), mapper.readTree(rec.body), "request");
            assertThat(mapper.readTree(rec.body).get("amount").decimalValue()).isEqualByComparingTo("2915.00");

            assertThat(intent.paymentId()).isEqualTo(UUID.fromString("0b3f6a2e-58c1-4d7e-9a8b-2f1c3d4e5f60"));
            assertThat(intent.checkoutUrl()).startsWith("http");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refuses_an_intent_whose_amount_differs_from_the_fare() {
        // MONEY: the provider answering with any other amount must never reach the user's checkout page.
        var rec = new HttpContract.Recorded();
        HttpServer server = contract.stubProvider(rec);
        try {
            var req = request();
            var cheaper = new PaymentClient.CreatePayment(req.pnr(), req.userId(), new BigDecimal("2915.01"),
                    req.currency(), req.payBy());
            assertThatThrownBy(() -> client(server).create(cheaper)).isInstanceOf(IllegalStateException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void provides_the_contact_shape_notification_expects() throws Exception {
        HttpContract c = HttpContract.load("notification-to-reservation.contact");
        var m = InternalContactController.class.getMethod("contact", String.class,
                org.springframework.security.core.Authentication.class);
        assertThat(c.request().get("path").asText())
                .matches(m.getAnnotation(GetMapping.class).value()[0].replace("{pnr}", "[1-9][0-9]{9}"));
        var sample = new InternalContactController.Contact("2400000018", "9820012345", "sana@example.com");
        HttpContract.assertSameShape(c.response().get("body"), mapper.readTree(mapper.writeValueAsString(sample)), "response");
    }
}
