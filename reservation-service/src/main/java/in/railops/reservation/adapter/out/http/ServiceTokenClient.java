package in.railops.reservation.adapter.out.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** reservation-service's client_credentials token (scope payments:create). Logic lives in railops-common. */
@Component
public class ServiceTokenClient extends in.railops.common.security.ServiceTokenClient {

    public ServiceTokenClient(RestClient.Builder builder,
                              @Value("${railops.security.token-uri}") String tokenUri,
                              @Value("${railops.security.client-id}") String clientId,
                              @Value("${railops.security.client-secret}") String clientSecret,
                              @Value("${railops.security.client-scope:payments:create payments:read}") String scope) {
        super(builder, tokenUri, clientId, clientSecret, scope);
    }
}
