package spikes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * S5: SLF4J fluent key-values in Boot's own structured JSON logging, and a members customizer as the hook for
 * the platform's masking. Expectation: the key-value appears as a JSON member, and the customizer masks a
 * password in the message without the service using a platform type.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = S5StructuredLoggingTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "logging.structured.format.console=ecs",
                "logging.structured.json.customizer=spikes.S5StructuredLoggingTest$Masking"
        })
class S5StructuredLoggingTest {

    private static final Logger LOG = LoggerFactory.getLogger(S5StructuredLoggingTest.class);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    /** Stands in for the platform's masking, applied to every string value Boot writes. */
    public static class Masking implements StructuredLoggingJsonMembersCustomizer<Object> {
        @Override
        public void customize(JsonWriter.Members<Object> members) {
            members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class,
                    value -> value == null ? null : value.replaceAll("password=\\S+", "password=****")));
        }
    }

    @Test
    void keyValuesRenderAndMaskingApplies(CapturedOutput output) {
        LOG.atInfo().addKeyValue("orderId", "42").log("login with password=hunter2");
        assertThat(output.getOut()).contains("\"orderId\":\"42\"");
        assertThat(output.getOut()).contains("password=****");
        assertThat(output.getOut()).doesNotContain("hunter2");
    }
}
