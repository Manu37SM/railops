package in.railops.reservation.application.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Predictions are advisory. If seat allocation, promotion, charting, refunds or quota policy ever start reading them,
 * a model error becomes a passenger losing a berth. This keeps the dependency one-way.
 */
class PredictionIsolationTest {

    static final Path MAIN = Path.of("src/main/java/in/railops/reservation");

    @Test
    void no_allocation_or_money_path_depends_on_the_prediction_package() throws IOException {
        List<Path> guarded;
        try (Stream<Path> s = Files.walk(MAIN)) {
            guarded = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.startsWith(MAIN.resolve("domain"))
                            || p.getFileName().toString().matches(
                            "(Allocation|Promotion|Chart|Booking|Cancellation|PaymentConfirmation|HoldReleaser|"
                                    + "RunCancellation|Availability)\\w*\\.java")
                            && p.startsWith(MAIN.resolve("application")))
                    .toList();
        }
        assertThat(guarded).as("the scan must actually find the guarded classes").hasSizeGreaterThan(10);
        for (Path p : guarded) {
            assertThat(Files.readString(p)).as(p.toString())
                    .doesNotContain("application.prediction")
                    .doesNotContain("WaitlistPrediction")
                    .doesNotContain("PredictionRepository");
        }
    }
}
