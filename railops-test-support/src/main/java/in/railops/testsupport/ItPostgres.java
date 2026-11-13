package in.railops.testsupport;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * A fresh, empty PostgreSQL database per test class, so integration tests never share state (Phase 10 showed how a
 * suite can pass only because of leftovers).
 *
 * <p>Two sources, chosen at runtime:
 * <ul>
 *   <li>{@code RAILOPS_IT_PG=jdbc:postgresql://host:5432/postgres?user=u&password=p}: an existing server whose user
 *       has CREATEDB (local dev, CI with a service container). Each call creates {@code it_<random>}; it is dropped
 *       when the JVM exits.</li>
 *   <li>otherwise Testcontainers {@code postgres:16-alpine}, one container per JVM, a database per call.</li>
 * </ul>
 *
 * <p>Must be real PostgreSQL, never H2: the design depends on GiST exclusion constraints, range types, partitioning,
 * advisory locks, {@code FOR UPDATE} ordering and deferred constraint triggers, none of which an emulator has.
 */
public final class ItPostgres {

    public record Db(String url, String user, String password) {
    }

    private static PostgreSQLContainer<?> container;

    private ItPostgres() {
    }

    public static synchronized Db newDatabase() {
        String admin = System.getenv("RAILOPS_IT_PG");
        String adminUrl;
        String user;
        String password;
        if (admin != null && !admin.isBlank()) {
            adminUrl = admin;
            user = param(admin, "user");
            password = param(admin, "password");
        } else {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:16-alpine").withUsername("railops").withPassword("railops");
                container.start();
            }
            adminUrl = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }
        String name = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        exec(adminUrl, user, password, "CREATE DATABASE " + name);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                exec(adminUrl, user, password, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            } catch (RuntimeException ignored) {
                // best effort; a leftover it_* database is harmless and easy to spot
            }
        }));
        String base = adminUrl.contains("?") ? adminUrl.substring(0, adminUrl.indexOf('?')) : adminUrl;
        return new Db(base.substring(0, base.lastIndexOf('/') + 1) + name, user, password);
    }

    private static void exec(String url, String user, String password, String sql) {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("integration database setup failed: " + e.getMessage(), e);
        }
    }

    private static String param(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String kv : url.substring(q + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                return p[1];
            }
        }
        return null;
    }
}
