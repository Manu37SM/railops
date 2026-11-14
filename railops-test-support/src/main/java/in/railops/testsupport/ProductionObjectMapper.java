package in.railops.testsupport;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The ObjectMapper exactly as the service builds it: Boot's Jackson auto-configuration plus the service's own
 * {@code application.yml} {@code spring.jackson.*} settings. Contract tests must serialise with this, not with
 * {@code new ObjectMapper()}: e.g. reservation's {@code default-property-inclusion: non_null} drops null fields
 * from the wire, which a hand-made mapper would hide.
 */
public final class ProductionObjectMapper {

    private ProductionObjectMapper() {
    }

    public static ObjectMapper load() {
        AtomicReference<ObjectMapper> out = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(ctx -> out.set(ctx.getBean(ObjectMapper.class)));
        return out.get();
    }
}
