package in.railops.notification;

import com.sun.net.httpserver.HttpServer;
import in.railops.common.security.ServiceTokenClient;
import in.railops.testsupport.HttpContract;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** notification as CONSUMER of reservation's contact lookup. */
class ContactHttpConsumerContractTest {

    @Test
    void asks_for_the_contact_as_agreed_and_reads_the_phone() {
        HttpContract c = HttpContract.load("notification-to-reservation.contact");
        var rec = new HttpContract.Recorded();
        HttpServer server = c.stubProvider(rec);
        try {
            ServiceTokenClient tokens = mock(ServiceTokenClient.class);
            when(tokens.token()).thenReturn("svc-token");
            var notifier = new NotificationApplication.Notifier(null, null, RestClient.builder(), tokens, null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(notifier.contactPhone("2400000018")).contains("9820012345");
            assertThat(rec.method).isEqualTo("GET");
            assertThat(rec.path).isEqualTo(c.request().get("path").asText());
            assertThat(rec.headers.get("authorization")).isEqualTo("Bearer svc-token");
        } finally {
            server.stop(0);
        }
    }
}
