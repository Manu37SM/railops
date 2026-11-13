package in.railops.common.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Schema migrations as a separate, short-lived process (compose one-shot service, Kubernetes init container), so
 * the long-running service NEVER holds the schema owner's credentials: it connects as a DML-only role with
 * {@code spring.flyway.enabled=false}. A compromised app pod can't run DDL, disable the audit/ledger triggers, or
 * grant itself more.
 *
 * <p>No Spring context: the service's own secrets (JWT keys, PII keys) are not needed and not mounted here.
 * Runs from the image's extracted layers:
 * <pre>java -cp '/opt/app/BOOT-INF/classes:/opt/app/BOOT-INF/lib/*' in.railops.common.db.Migrate</pre>
 *
 * <p>Environment: DB_URL, DB_MIGRATION_USER, DB_MIGRATION_PASSWORD_FILE (or DB_MIGRATION_PASSWORD), DB_SCHEMA,
 * APP_ROLE (the runtime role that V*__runtime_grants.sql grants to). Exit 0 = schema current.
 */
public final class Migrate {

    private Migrate() {
    }

    public static void main(String[] args) {
        try {
            MigrateResult r = flyway(System.getenv()).migrate();
            System.out.printf("migrate: schema %s now at version %s (%d migrations applied)%n",
                    r.schemaName, r.targetSchemaVersion, r.migrationsExecuted);
            System.exit(0);
        } catch (RuntimeException e) {
            System.err.println("migrate: FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    static Flyway flyway(Map<String, String> env) {
        String schema = require(env, "DB_SCHEMA");
        if (!schema.matches("[a-z_]+")) {
            throw new IllegalArgumentException("invalid DB_SCHEMA");
        }
        String appRole = require(env, "APP_ROLE");
        if (!appRole.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid APP_ROLE");   // interpolated into GRANT statements
        }
        return Flyway.configure()
                .dataSource(require(env, "DB_URL"), require(env, "DB_MIGRATION_USER"), password(env))
                .schemas(schema)
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRole", appRole))
                .connectRetries(10)               // the database may still be starting (compose, fresh cluster)
                .lockRetryCount(120)              // a concurrent migrator (rolling deploy) holds the lock: wait
                .validateMigrationNaming(true)
                .load();
    }

    private static String password(Map<String, String> env) {
        String file = env.get("DB_MIGRATION_PASSWORD_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read DB_MIGRATION_PASSWORD_FILE " + file, e);
            }
        }
        return require(env, "DB_MIGRATION_PASSWORD");
    }

    private static String require(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return v;
    }
}
