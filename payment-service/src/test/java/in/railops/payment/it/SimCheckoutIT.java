package in.railops.payment.it;

import in.railops.payment.gateway.GatewayPort;
import in.railops.payment.gateway.SimulatedGateway;
import in.railops.payment.service.PaymentService;
import in.railops.payment.web.PaymentControllers.InternalSimCheckout;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 5: reservation (for the booking's owner) finishes the simulated checkout of a payment. */
@SpringBootTest
@ActiveProfiles("local")
class SimCheckoutIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.scheduling.enabled", () -> "false");
        r.add("spring.kafka.listener.auto-startup", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @MockitoBean SimulatedGateway gateway;
    @Autowired PaymentService payments;
    @Autowired InternalSimCheckout checkout;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void gateway() {
        when(gateway.name()).thenReturn("simgw");
        when(gateway.createOrder(any(), any(), anyString(), anyString())).thenAnswer(i -> new GatewayPort.Order("ord_" + UUID.randomUUID(), "x"));
        when(gateway.checkoutUrl(anyString())).thenReturn("x");
    }

    UUID pending() {
        String pnr = "4" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        return payments.createOrGet(new PaymentService.CreateRequest(pnr, UUID.randomUUID(), new BigDecimal("845.00"), "INR",
                Instant.now().plusSeconds(600))).paymentId();
    }

    String orderOf(UUID id) {
        return jdbc.sql("SELECT gateway_order_id FROM payment.payment WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void a_pending_payment_is_adopted_and_paid_at_the_simulated_gateway() {
        UUID id = pending();
        var res = checkout.pay(id, "success");
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        verify(gateway).adopt(orderOf(id), id, new BigDecimal("845.00"));
        verify(gateway).customerPays(eq(orderOf(id)), eq("success"), eq(null), eq(1), eq(false));
    }

    @Test
    void an_already_decided_payment_is_not_paid_twice() {
        UUID id = pending();
        jdbc.sql("UPDATE payment.payment SET status = 'CAPTURED', captured_amount = amount WHERE id = :id").param("id", id).update();
        var res = checkout.pay(id, "success");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "CAPTURED");
        verify(gateway, never()).customerPays(anyString(), anyString(), any(), anyInt(), anyBoolean());
    }

    @Test
    void unknown_payments_and_odd_outcomes_are_refused() {
        assertThat(checkout.pay(UUID.randomUUID(), "success").getStatusCode().value()).isEqualTo(404);
        assertThat(checkout.pay(pending(), "maybe").getStatusCode().value()).isEqualTo(400);
        verify(gateway, never()).customerPays(anyString(), anyString(), any(), anyInt(), anyBoolean());
    }
}
