package in.railops.testsupport;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Event contracts live in {@code contracts/events/<EventType>.v<N>.schema.json} at the repository root and are the
 * single source of truth shared by producers and consumers:
 * <ul>
 *   <li>producer tests serialise the real payload record and validate it against the schema;</li>
 *   <li>consumer tests parse the golden examples in {@code contracts/events/examples/} with the real listener code.</li>
 * </ul>
 * A producer that renames a field fails its own build, before the consumer finds out in production.
 */
public final class EventContracts {

    private static final String BASE = "https://contracts.railops.in/events/";
    private static SchemaRegistry registry;

    private EventContracts() {
    }

    public static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("contracts/events"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("contracts/events not found above " + Path.of("").toAbsolutePath());
        }
        return p.resolve("contracts/events");
    }

    private static synchronized SchemaRegistry registry() {
        if (registry == null) {
            Map<String, String> schemas = new HashMap<>();
            try (Stream<Path> files = Files.list(root())) {
                for (Path f : files.filter(f -> f.toString().endsWith(".schema.json")).toList()) {
                    schemas.put(BASE + f.getFileName(), Files.readString(f));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            registry = SchemaRegistry.withDialect(Dialects.getDraft202012(), b -> b.schemas(schemas));
        }
        return registry;
    }

    /** Validation errors (empty = valid) of a payload against {@code <eventType>.v<version>.schema.json}. */
    public static List<Error> validate(String eventType, int version, String payloadJson) {
        Schema schema = registry().getSchema(SchemaLocation.of(BASE + eventType + ".v" + version + ".schema.json"));
        return schema.validate(payloadJson, InputFormat.JSON,
                ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
    }

    public static String example(String name) {
        try {
            return Files.readString(root().resolve("examples").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<String> schemaNames() {
        try (Stream<Path> files = Files.list(root())) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".schema.json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
