package spikes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

/**
 * S5b (Spring-native Phase 3, Gate A): can a members customizer in Boot's Logstash-format structured logging carry
 * the platform's masking? Checks what a value processor sees: nested key-value maps and lists, a record, a double,
 * an MDC key, the message and the stack trace; whether it knows the member name; whether the customizer can read the
 * Environment (for extra patterns); whether the platform's field names can be kept by renaming; and whether a
 * customizer registered in META-INF/spring.factories applies alongside one a service names in
 * logging.structured.json.customizer.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = S5bStructuredMaskingTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "logging.structured.format.console=logstash",
                "logging.structured.json.customizer=spikes.S5bStructuredMaskingTest$ServiceOwn",
                "logging.structured.json.rename.logger_name=logger",
                "logging.structured.json.exclude=@version,thread_name,level_value",
                "logging.structured.json.add.service=spike-service",
                "spike.extra-pattern=CASE-[0-9]{6}"
        })
class S5bStructuredMaskingTest {

    private static final Logger LOG = LoggerFactory.getLogger(S5bStructuredMaskingTest.class);

    /** Every (path, value type) the value processor was called with. */
    static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    static volatile String extraPattern = "unset";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    record Card(String holder, double pan, String password) {
    }

    public static class Masking implements StructuredLoggingJsonMembersCustomizer<Object> {

        public Masking(Environment environment) {
            extraPattern = environment.getProperty("spike.extra-pattern", "missing");
        }

        @Override
        public void customize(JsonWriter.Members<Object> members) {
            members.applyingValueProcessor(new JsonWriter.ValueProcessor<Object>() {
                @Override
                public Object processValue(JsonWriter.MemberPath path, Object value) {
                    SEEN.add(path + " : " + (value == null ? "null" : value.getClass().getSimpleName()));
                    if (path != null && path.name() != null && path.name().toLowerCase().contains("password")) {
                        return "****";
                    }
                    if (value instanceof String text) {
                        return text.replaceAll("password=\\S+", "password=****").replaceAll(extraPattern, "****");
                    }
                    if (value instanceof Double d && d > 1e15) {
                        return "****";
                    }
                    return value;
                }
            });
        }
    }

    /** A service's own customizer, set by property: it must apply next to the platform's from spring.factories. */
    public static class ServiceOwn implements StructuredLoggingJsonMembersCustomizer<Object> {
        @Override
        public void customize(JsonWriter.Members<Object> members) {
            members.add("serviceCustomizer", "applied");
        }
    }

    @Test
    void whatTheValueProcessorSees(CapturedOutput output) throws Exception {
        MDC.put("authorization", "Basic SAMPLE-NOT-A-CREDENTIAL");
        MDC.put("tenant", "t-1");
        try {
            LOG.atInfo()
                    .addKeyValue("orderId", "42")
                    .addKeyValue("nested", Map.of("password", "nested-pw-1", "note", "ref CASE-123456"))
                    .addKeyValue("list", List.of("password=list-pw-2", "ok"))
                    .addKeyValue("card", new Card("alice", 4111111111111111.0, "record-pw-3"))
                    .addKeyValue("amount", 4111111111111111.0)
                    .log("login with password=message-pw-4");
            LOG.error("failed", new IllegalStateException("db login password=exception-pw-5"));
        } finally {
            MDC.clear();
        }
        String out = output.getOut();
        Files.writeString(Path.of("target/s5b-output.txt"), out + "\n--- seen ---\n" + String.join("\n", SEEN.stream().sorted().toList())
                + "\n--- extraPattern ---\n" + extraPattern + "\n");
        assertThat(out).contains("\"orderId\":\"42\"").contains("\"serviceCustomizer\":\"applied\"")
                .contains("password=****").doesNotContain("message-pw-4");
    }
}
