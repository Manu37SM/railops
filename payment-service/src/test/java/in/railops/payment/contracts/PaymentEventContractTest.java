package in.railops.payment.contracts;

import tools.jackson.databind.ObjectMapper;
import in.railops.payment.contracts.PaymentEvents.*;
import in.railops.payment.service.RefundService.RefundRequested;
import in.railops.testsupport.EventContracts;
import in.railops.testsupport.ProductionObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventContractTest {

    static ObjectMapper mapper;
    static final UUID PAY = UUID.fromString("0b3f6a2e-58c1-4d7e-9a8b-2f1c3d4e5f60");

    @BeforeAll
    static void load() {
        mapper = ProductionObjectMapper.load();
    }

    static Stream<Arguments> published() {
        return Stream.of(
                Arguments.of("PaymentCaptured", new PaymentCaptured(PAY, "2400000018", new BigDecimal("2915.00"), "INR")),
                Arguments.of("PaymentFailed", new PaymentFailed(PAY, "2400000018", "DECLINED")),
                Arguments.of("RefundSucceeded", new RefundSucceeded(UUID.randomUUID(), PAY, "2400000018", new BigDecimal("2795.00"))),
                Arguments.of("RefundNeedsReview", new RefundNeedsReview(UUID.randomUUID(), PAY, new BigDecimal("2795.00"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("published")
    void published_payload_matches_its_schema(String type, Object payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        assertThat(EventContracts.validate(type, 1, json)).as(json).isEmpty();
    }

    @Test
    void money_keeps_its_paise_on_the_wire() throws Exception {
        // MONEY: BigDecimal -> JSON number must not pass through double ("2915.1" or 2.91500E3 would still
        // validate as a number but is a reconciliation headache). Assert the exact token.
        String json = mapper.writeValueAsString(new PaymentCaptured(PAY, "2400000018", new BigDecimal("2915.10"), "INR"));
        assertThat(json).contains("\"amount\":2915.10");
    }

    @Test
    void a_zero_capture_is_not_a_valid_event() throws Exception {
        String json = mapper.writeValueAsString(new PaymentCaptured(PAY, "2400000018", BigDecimal.ZERO, "INR"));
        assertThat(EventContracts.validate("PaymentCaptured", 1, json)).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------- consumed (reservation)

    @Test
    void consumes_refund_requested_as_reservation_publishes_it() throws Exception {
        String json = EventContracts.example("RefundRequested.json");
        assertThat(EventContracts.validate("RefundRequested", 1, json)).isEmpty();
        RefundRequested r = mapper.readValue(json, RefundRequested.class);
        assertThat(r.amount()).isEqualByComparingTo("2795.00");
        assertThat(r.paymentId()).isEqualTo(PAY);
        // The idempotency key is what makes a redelivered RefundRequested refund at most once. It must survive.
        assertThat(r.idempotencyKey()).startsWith("cancel:");
    }

    @Test
    void every_field_reservation_sends_has_a_home_in_the_consumer_record() {
        // Payment declares its own copy of RefundRequested. If reservation adds a field, the schema changes and
        // this flags that payment's copy silently drops it (tolerant reader would hide it otherwise).
        var schemaFields = EventContracts.validate("RefundRequested", 1, "{}").stream()
                .map(e -> e.getMessage()).toList();
        var recordFields = Stream.of(RefundRequested.class.getRecordComponents()).map(c -> c.getName()).toList();
        for (String f : recordFields) {
            assertThat(schemaFields).anySatisfy(m -> assertThat(m).contains(f));
        }
        assertThat(schemaFields).hasSameSizeAs(recordFields);
    }
}
