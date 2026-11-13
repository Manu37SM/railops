package in.railops.reservation.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import in.railops.reservation.contracts.ReservationEvents.*;
import in.railops.testsupport.EventContracts;
import in.railops.testsupport.ProductionObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer side: what reservation-service PUBLISHES matches contracts/events. Consumer side: what it CONSUMES
 * (golden examples written against the schema) deserialises into the records the listeners use.
 */
class ReservationEventContractTest {

    static ObjectMapper mapper;
    static final UUID PAY = UUID.fromString("0b3f6a2e-58c1-4d7e-9a8b-2f1c3d4e5f60");

    @BeforeAll
    static void mapper() {
        mapper = ProductionObjectMapper.load();
    }

    static Stream<Arguments> published() {
        return Stream.of(
                Arguments.of("BookingInitiated", new BookingInitiated("2400000018", 1, LocalDate.of(2026, 11, 20), "3A", "GN",
                        0, 4, new BigDecimal("2915.00"), 2, Instant.parse("2026-11-12T05:30:00Z"))),
                Arguments.of("BookingConfirmed", new BookingConfirmed("2400000018", 1, PAY, false)),
                Arguments.of("BookingExpired", new BookingExpired("2400000018", 1)),
                Arguments.of("RefundRequested", new RefundRequested("2400000018", PAY, new BigDecimal("2795.00"),
                        "BOOKING_EXPIRED", "capture-refund:" + PAY)),
                Arguments.of("PassengerPromoted", new PassengerPromoted("2400000018", 41, "CNF", "CNF B1/12 LB")),
                Arguments.of("InventoryChanged", new InventoryChanged(1, "3A", "GN", 0, 4, "CANCELLED")),
                // The run-wide variant: class and quota are null in Java. With non_null inclusion they are ABSENT
                // on the wire; the schema must accept that, and consumers must read absent as null.
                Arguments.of("InventoryChanged", new InventoryChanged(1, null, null, 0, 0, "RUN_STATUS")),
                Arguments.of("BookingCancelled", new BookingCancelled("2400000018", UUID.randomUUID(), List.of(1, 2),
                        new BigDecimal("2795.00"), "CANCELLED")),
                Arguments.of("WaitlistChartCancelled", new WaitlistChartCancelled("2400000018", List.of(3),
                        new BigDecimal("1360.00"), "PARTIALLY_CANCELLED")),
                Arguments.of("BookingRunCancelled", new BookingRunCancelled("2400000018", 1, new BigDecimal("2915.00"),
                        "Flooding between RTM and KOTA")),
                Arguments.of("RunCancelled", new in.railops.reservation.application.RunCancellationService.RunCancelled(
                        1, "Flooding", UUID.randomUUID().toString())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("published")
    void published_payload_matches_its_schema(String type, Object payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        assertThat(EventContracts.validate(type, 1, json)).as(json).isEmpty();
    }

    @Test
    void every_published_type_has_a_schema() {
        List<String> schemas = EventContracts.schemaNames();
        published().map(a -> a.get()[0] + ".v1.schema.json").forEach(n -> assertThat(schemas).contains(n));
    }

    @Test
    void a_renamed_field_is_caught() throws Exception {
        // The regression this test exists for: "refundAmount" -> "refund" would have reached notification as "".
        String drifted = mapper.writeValueAsString(new BookingCancelled("2400000018", UUID.randomUUID(), List.of(1),
                new BigDecimal("10.00"), "CANCELLED")).replace("refundAmount", "refund");
        assertThat(EventContracts.validate("BookingCancelled", 1, drifted)).isNotEmpty();
    }

    @Test
    void schema_rejects_a_class_on_a_run_status_change_and_a_missing_class_otherwise() {
        assertThat(EventContracts.validate("InventoryChanged", 1,
                "{\"trainRunId\":1,\"classCode\":\"3A\",\"fromSeq\":0,\"toSeq\":0,\"reason\":\"RUN_STATUS\"}")).isNotEmpty();
        assertThat(EventContracts.validate("InventoryChanged", 1,
                "{\"trainRunId\":1,\"fromSeq\":0,\"toSeq\":4,\"reason\":\"CANCELLED\"}")).isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------------- consumed

    @Test
    void consumes_payment_captured_as_payment_service_publishes_it() throws Exception {
        String json = EventContracts.example("PaymentCaptured.json");
        assertThat(EventContracts.validate("PaymentCaptured", 1, json)).isEmpty();
        PaymentCaptured e = mapper.readValue(json, PaymentCaptured.class);
        assertThat(e.paymentId()).isEqualTo(PAY);
        assertThat(e.amount()).isEqualByComparingTo("2915.00");   // MONEY: BigDecimal, no double rounding
        assertThat(e.pnr()).isEqualTo("2400000018");
    }

    @Test
    void consumes_run_status_change_with_absent_class_as_null() throws Exception {
        InventoryChanged e = mapper.readValue("{\"trainRunId\":1,\"fromSeq\":0,\"toSeq\":0,\"reason\":\"RUN_STATUS\"}",
                InventoryChanged.class);
        assertThat(e.classCode()).isNull();
        assertThat(e.freesCapacity()).isFalse();
    }

    @Test
    void tolerant_reader_ignores_fields_added_by_a_newer_producer() throws Exception {
        String newer = EventContracts.example("PaymentCaptured.json").replace("}", ",\"gatewayFee\":12.5}");
        assertThat(mapper.readValue(newer, PaymentCaptured.class).amount()).isEqualByComparingTo("2915.00");
    }
}
