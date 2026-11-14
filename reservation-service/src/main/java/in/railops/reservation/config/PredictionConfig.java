package in.railops.reservation.config;

import tools.jackson.databind.ObjectMapper;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.PredictionRepository;
import in.railops.reservation.application.TransactionRunner;
import in.railops.reservation.application.prediction.PredictionMonitor;
import in.railops.reservation.application.prediction.WaitlistModel;
import in.railops.reservation.application.prediction.WaitlistPredictionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;

/**
 * Off by default. The bundled model was trained on SYNTHETIC data; showing its numbers to real passengers would be a
 * false statement about their ticket. Enable only with a model trained and evaluated on real chart outcomes, or in
 * local/sim environments (application-local.yml).
 */
@Configuration
@ConditionalOnProperty(name = "railops.prediction.wl.enabled", havingValue = "true")
public class PredictionConfig {

    private static final Logger log = LoggerFactory.getLogger(PredictionConfig.class);

    @Bean
    WaitlistModel waitlistModel(@Value("${railops.prediction.wl.model-location:classpath:ml/wl-model.json}")
                                Resource location, ObjectMapper json) throws IOException {
        try (InputStream in = location.getInputStream()) {
            WaitlistModel m = WaitlistModel.load(in, json);
            log.info("loaded WL model {} from {} (trained on: {})", m.version(), location.getDescription(),
                    m.trainedOn());
            return m;
        }
    }

    @Bean
    WaitlistPredictionService waitlistPredictionService(WaitlistModel model, PredictionRepository repo,
                                                        BookingRepository bookings, TransactionRunner tx,
                                                        ObjectMapper json, MeterRegistry meters,
                                                        @Value("${railops.prediction.wl.disclaimer}") String disclaimer) {
        return new WaitlistPredictionService(model, repo, bookings, tx, json, meters, Clock.systemUTC(), disclaimer);
    }

    @Bean
    PredictionMonitor predictionMonitor(PredictionRepository repo, TransactionRunner tx, WaitlistModel model,
                                        MeterRegistry meters) {
        return new PredictionMonitor(repo, tx, model, meters, Clock.systemUTC());
    }
}
