package in.railops.identity.web;

import in.railops.identity.crypto.JwtKeys;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
public class JwksController {

    private final JwtKeys keys;

    public JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    /** Public keys only. Cacheable for 5 minutes: key rotation must keep the old key published longer than that. */
    @io.swagger.v3.oas.annotations.Operation(summary = "Public signing keys (JWKS)", description = "Includes the previous key during rotation. Cacheable for 5 minutes.")
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwkSet().toJSONObject(true));
    }
}
