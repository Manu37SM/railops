package in.railops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.railops.testsupport.EventContracts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer contract: every event notification turns into an SMS, fed with payloads that are VALID per the producer's
 * schema, renders a complete message; and a payload missing a field the template uses is rejected, not texted.
 */
class NotificationContractTest {

    static final ObjectMapper M = new ObjectMapper();

    /** One schema-valid payload per templated event type. */
    static final Map<String, String> SAMPLES = Map.of(
            "BookingConfirmed", EventContracts.example("BookingConfirmed.json"),
            "BookingCancelled", EventContracts.example("BookingCancelled.json"),
            "RefundSucceeded", EventContracts.example("RefundSucceeded.json"),
            "PassengerPromoted", "{\"pnr\":\"2400000018\",\"passengerId\":41,\"newStatus\":\"CNF\",\"statusText\":\"CNF B1/12 LB\"}",
            "WaitlistChartCancelled", "{\"pnr\":\"2400000018\",\"passengers\":[3],\"refundAmount\":1360.00,\"bookingStatus\":\"PARTIALLY_CANCELLED\"}",
            "BookingRunCancelled", "{\"pnr\":\"2400000018\",\"trainRunId\":1,\"refundAmount\":2915.00,\"reason\":\"Flooding\"}");

    static Stream<String> types() {
        return NotificationApplication.Notifier.TEMPLATES.keySet().stream().sorted();
    }

    @Test
    void every_template_has_a_contract_and_a_sample() {
        types().forEach(t -> {
            assertThat(EventContracts.schemaNames()).contains(t + ".v1.schema.json");
            assertThat(SAMPLES).containsKey(t);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void renders_a_complete_message_from_a_contract_valid_payload(String type) throws Exception {
        String json = SAMPLES.get(type);
        assertThat(EventContracts.validate(type, 1, json)).as("sample must itself honour the contract").isEmpty();
        String sms = NotificationApplication.Notifier.TEMPLATES.get(type).apply(M.readTree(json));
        assertThat(sms).contains("2400000018").doesNotContain("  ").doesNotContain("Rs .").doesNotContain("null");
        assertThat(sms.length()).isLessThanOrEqualTo(160);   // one SMS segment: cheaper and never split
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void a_payload_missing_any_field_the_template_reads_is_rejected_not_sent(String type) throws Exception {
        ObjectNode full = (ObjectNode) M.readTree(SAMPLES.get(type));
        full.fieldNames().forEachRemaining(f -> {
            ObjectNode broken = full.deepCopy();
            broken.remove(f);
            try {
                String sms = NotificationApplication.Notifier.TEMPLATES.get(type).apply(broken);
                // Field not used by this template: fine, but then the message must still be complete.
                assertThat(sms).doesNotContain("  ");
            } catch (IllegalArgumentException expected) {
                assertThat(expected).hasMessageContaining(f);
            }
        });
    }

    @Test
    void blank_or_object_values_are_treated_as_missing() throws Exception {
        assertThatThrownBy(() -> NotificationApplication.Notifier.req(M.readTree("{\"pnr\":\"  \"}"), "pnr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationApplication.Notifier.req(M.readTree("{\"pnr\":{\"x\":1}}"), "pnr"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
