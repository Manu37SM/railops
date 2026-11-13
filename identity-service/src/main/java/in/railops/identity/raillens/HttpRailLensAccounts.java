package in.railops.identity.raillens;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.railops.identity.service.IdentityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Calls RailLens' admin endpoints with the RailOps admin's own access token (RailLens accepts it since Phase 4B). */
@Component
public class HttpRailLensAccounts implements RailLensAccounts {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PendingDto(long raillensUserId, String email, String passwordHash, boolean admin) {
    }

    private final RestClient client;

    public HttpRailLensAccounts(RestClient.Builder builder,
                                @Value("${railops.raillens.base-url:http://localhost:8080/api/v1}") String baseUrl) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(120));  // RailLens on a free plan may need 1-2 minutes to wake up (gateway allows 130 s)
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public List<Pending> pending(long afterId, int limit, String adminBearer) {
        List<PendingDto> page = call(() -> client.get()
                .uri(u -> u.path("/admin/railops/accounts").queryParam("afterId", afterId).queryParam("limit", limit).build())
                .header("Authorization", "Bearer " + adminBearer).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(new ParameterizedTypeReference<List<PendingDto>>() { }));
        return page == null ? List.of()
                : page.stream().map(p -> new Pending(p.raillensUserId(), p.email(), p.passwordHash(), p.admin())).toList();
    }

    @Override
    public LinkResult link(List<Link> links, String adminBearer) {
        LinkResult result = call(() -> client.post().uri("/admin/railops/links")
                .header("Authorization", "Bearer " + adminBearer).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("links", links)).retrieve().body(LinkResult.class));
        return result == null ? new LinkResult(0, 0, List.of()) : result;
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new IdentityException("RAILLENS_REFUSED", HttpStatus.BAD_GATEWAY, "RailLens did not accept your admin "
                        + "sign-in. Check that RailLens has RAILLENS_RAILOPS_JWKS_URL and RAILLENS_RAILOPS_ISSUER set.");
            }
            throw new IdentityException("RAILLENS_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "RailLens answered " + e.getStatusCode().value() + "; try again in a minute");
        } catch (RestClientException e) {
            throw new IdentityException("RAILLENS_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "RailLens could not be reached; try again in a minute");
        }
    }
}
