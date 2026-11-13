package in.railops.reservation.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.railops.reservation.application.schedule.TimetablePort;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.policy.RunPlanner.Timetable;
import in.railops.reservation.domain.policy.RunPlanner.TimetableStop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a train's published timetable from the RailLens public API ({@code GET /trains/{number}}, no credentials).
 *
 * <p>Only used when staff import runs, never on the booking path, so it favours patience over speed: RailLens on a
 * free plan can take up to a minute to wake up. No retry: staff can press the button again.
 */
@Component
public class RailLensTimetableClient implements TimetablePort {

    private static final Pattern TRAIN_NO = Pattern.compile("\\d{5}");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrainDetails(String trainNumber, String trainName, List<RouteStop> route) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteStop(Integer sequenceNo, String stationCode, LocalTime arrivalTime, LocalTime departureTime, Integer distance) {
    }

    private final RestClient client;

    public RailLensTimetableClient(RestClient.Builder builder,
                                  @Value("${railops.raillens.base-url:http://localhost:8080/api/v1}") String baseUrl) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(70));
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public Optional<Timetable> fetch(String trainNo) {
        if (trainNo == null || !TRAIN_NO.matcher(trainNo).matches()) {
            return Optional.empty();   // never put unchecked input into the URL
        }
        TrainDetails details;
        try {
            details = client.get()
                    .uri("/trains/{number}", trainNo)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (req, res) -> {
                        throw new NotFound();
                    })
                    .body(TrainDetails.class);
        } catch (NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            throw unavailable("RailLens answered " + statusOf(e.getStatusCode()));
        } catch (RestClientException e) {
            throw unavailable("RailLens could not be reached");
        }
        if (details == null || details.route() == null) {
            throw unavailable("RailLens returned no timetable");
        }
        List<RouteStop> route = details.route().stream()
                .sorted((a, b) -> Integer.compare(a.sequenceNo() == null ? 0 : a.sequenceNo(), b.sequenceNo() == null ? 0 : b.sequenceNo()))
                .toList();
        return Optional.of(new Timetable(details.trainNumber(), details.trainName(), route.stream()
                .map(s -> new TimetableStop(s.stationCode() == null ? null : s.stationCode().trim().toUpperCase(java.util.Locale.ROOT),
                        s.arrivalTime(), s.departureTime(), s.distance()))
                .toList()));
    }

    private static String statusOf(HttpStatusCode code) {
        return String.valueOf(code.value());
    }

    private static DomainException unavailable(String why) {
        return new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, why + "; try again in a minute");
    }

    private static final class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }
}
