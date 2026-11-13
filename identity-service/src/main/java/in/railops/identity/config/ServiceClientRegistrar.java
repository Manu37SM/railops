package in.railops.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Upserts service clients from deployment config: {@code railops.service-clients=id|secret|scope1 scope2;...}.
 * Secrets come from the secret store and are stored only as Argon2 hashes. Rotating a secret is a
 * config change plus a restart of the client service.
 */
@Component
public class ServiceClientRegistrar implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String spec;

    public ServiceClientRegistrar(JdbcClient jdbc, PasswordEncoder passwords,
                                  @Value("${railops.service-clients:}") String spec) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.spec = spec;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String entry : spec.split(";")) {
            String[] p = entry.trim().split("\\|");
            if (p.length != 3 || p[1].length() < 24) {
                if (!entry.isBlank()) {
                    throw new IllegalStateException("invalid service client entry (secret must be >= 24 chars)");
                }
                continue;
            }
            jdbc.sql("""
                            INSERT INTO identity.service_client (client_id, secret_hash, allowed_scopes)
                            VALUES (:id, :hash, string_to_array(:scopes, ' '))
                            ON CONFLICT (client_id) DO UPDATE SET secret_hash = EXCLUDED.secret_hash,
                                                                  allowed_scopes = EXCLUDED.allowed_scopes
                            """)
                    .param("id", p[0]).param("hash", passwords.encode(p[1])).param("scopes", p[2].trim())
                    .update();
        }
    }
}
