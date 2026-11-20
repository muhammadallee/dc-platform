package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Masking through a real boot of Boot's structured logging with the platform's defaults (D102, D141): every member
 * (message, MDC, key-value pairs, exception message, stack trace), the extra patterns and the switch, strict MDC mode,
 * injection-safe lines, a service's own customizer next to the platform's, and a log file.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedactedJsonLogOutputTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";

    private final Logger log = LoggerFactory.getLogger("redaction.probe");

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
    static class Empty {
    }

    /** A service's own customizer, named in logging.structured.json.customizer. */
    public static class ServiceCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {
        @Override
        public void customize(Members<Object> members) {
            members.add("region", "dxb");
        }
    }

    // Boot copies logging settings into JVM system properties when logging starts; each boot here starts afresh.
    private final java.util.Properties systemProperties = (java.util.Properties) System.getProperties().clone();

    @AfterEach
    void reset() {
        MDC.clear();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        System.setProperties((java.util.Properties) systemProperties.clone());
    }

    private ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("spring.application.name=orders").run(args);
    }

    @Test
    void masksEveryFieldTheEncoderWrites(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            MDC.put("authorization", "Bearer abcdefgh12345678");
            MDC.put("payer", "card 4111111111111111");
            MDC.put("contact", "fatima@customs.gov.ae, +971 50 123 4567");
            log.atError().addKeyValue("emiratesId", "784-1990-1234567-1")
                    .setCause(new IllegalStateException("rejected Bearer abcdefgh12345678"))
                    .log("login with password=hunter2 token {}", JWT);
        }
        String line = line(output, "login with");
        Map<String, Object> event = parse(line);
        assertThat(line).doesNotContain(JWT).doesNotContain("hunter2").doesNotContain("abcdefgh12345678")
                .doesNotContain("4111111111111111").doesNotContain("1234567");
        assertThat((String) event.get("message")).contains("password=****").contains("token ****");
        assertThat(event).containsEntry("authorization", "****").containsEntry("payer", "card ****")
                .containsEntry("contact", "****, ****").containsEntry("emiratesId", "****");
        assertThat((String) event.get("stack_trace")).contains("IllegalStateException").contains("Bearer ****");
    }

    @Test
    void cardNumbersLoggedAsNumbersAreMasked(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            log.atInfo().addKeyValue("card", 4111111111111111L).addKeyValue("cardAsDouble", 4111111111111111.0)
                    .addKeyValue("orderId", 1793001600123L).log("numeric {}", 4111111111111111.0);
        }
        String line = line(output, "numeric");
        Map<String, Object> event = parse(line);
        assertThat(line).doesNotContain("4111111111111111").doesNotContain("4.111111111111111E15");
        assertThat(event).containsEntry("card", "****").containsEntry("cardAsDouble", "****")
                .containsEntry("orderId", 1793001600123L).containsEntry("message", "numeric ****");
    }

    @Test
    void ordinaryValuesAreNotMasked(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("orderId", "400000000000001");
            log.info("took {} ms at {}", 250, 1793001600123L);
        }
        Map<String, Object> event = parse(line(output, "took 250 ms"));
        assertThat(event).containsEntry("message", "took 250 ms at 1793001600123")
                .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").containsEntry("orderId", "400000000000001");
    }

    @Test
    void redactionCanBeSwitchedOffAndExtended(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("--dc.platform.logging.redaction.enabled=false")) {
            log.info("off password=hunter2");
        }
        assertThat(line(output, "off password")).contains("password=hunter2");
        reset();
        try (ConfigurableApplicationContext context = boot("--dc.platform.logging.redaction.patterns[0]=CASE-\\d{6}")) {
            log.info("extended CASE-123456");
        }
        assertThat(parse(line(output, "extended"))).containsEntry("message", "extended ****");
    }

    @Test
    void strictMdcModeWritesOnlyPlatformAndListedKeys(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("--dc.platform.logging.mdc.include=tenant")) {
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("spanId", "00f067aa0ba902b7");
            MDC.put("tenant", "dubai");
            MDC.put("internalDebug", "x");
            log.atInfo().addKeyValue("unlisted", "y").log("strict probe");
        }
        Map<String, Object> event = parse(line(output, "strict probe"));
        assertThat(event).containsKeys("traceId", "spanId", "@timestamp", "level", "logger", "message", "service")
                .containsEntry("tenant", "dubai").doesNotContainKeys("internalDebug", "unlisted");
    }

    @Test
    void consumerMdcPassesThroughByDefault(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            MDC.put("internalDebug", "x");
            log.atInfo().addKeyValue("orderId", "42").log("default mdc probe");
        }
        assertThat(parse(line(output, "default mdc probe"))).containsEntry("internalDebug", "x")
                .containsEntry("orderId", "42");
    }

    @Test
    void userInputCannotForgeLinesOrFields(CapturedOutput output) {
        String hostile = "a\r\nb\"c {\"level\":\"ERROR\",\"message\":\"forged\"}";
        try (ConfigurableApplicationContext context = boot()) {
            MDC.put("input", hostile);
            log.info("injection probe {}", hostile);
        }
        List<String> lines = output.getOut().lines().filter(l -> l.contains("injection probe")).toList();
        assertThat(lines).hasSize(1);
        assertThat(output.getOut().lines().filter(l -> l.startsWith("{\"level\":\"ERROR\""))).isEmpty();
        Map<String, Object> event = parse(lines.get(0));
        assertThat(event).containsEntry("level", "INFO").containsEntry("message", "injection probe " + hostile)
                .containsEntry("input", hostile);
    }

    @Test
    void aServicesOwnCustomizerAddsToThePlatformMasking(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("--logging.structured.json.customizer="
                + ServiceCustomizer.class.getName())) {
            log.info("customized password=hunter2");
        }
        Map<String, Object> event = parse(line(output, "customized"));
        assertThat(event).containsEntry("region", "dxb").containsEntry("message", "customized password=****");
    }

    @Test
    void aLogFileIsMaskedJson(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("app.log");
        try (ConfigurableApplicationContext context = boot("--logging.file.name=" + file)) {
            log.info("to the file password=hunter2");
        }
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> l.contains("to the file"))
                .findFirst().orElseThrow(() -> new AssertionError("no line in the log file"));
        assertThat(parse(line)).containsEntry("message", "to the file password=****").containsEntry("service", "orders");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aThrowingMessageArgumentCostsOnlyItsPlaceInTheJsonLine(CapturedOutput output) {
        // Codex phase 3 round 1, finding 3, through a real console appender. The appender also has SLF4J format the
        // message before any encoder runs (Logback's prepareForDeferredProcessing), and SLF4J reports a throwing
        // toString() on stderr itself: a documented limit, as in v3, which wrote through the same appender.
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("lazy state");
            }
        };
        try (ConfigurableApplicationContext context = boot()) {
            log.info("argument probe {} {}", Map.of("password", "correct horse battery staple"), hostile);
        }
        Map<String, Object> event = parse(line(output, "argument probe"));
        assertThat(event).containsEntry("message", "argument probe {password=****} [unrenderable]");
    }

    @Test
    void consoleLoggingAfterAJsonStartInTheSameJvmIsConsole(CapturedOutput output) {
        // Codex phase 3 round 1, finding 18: no cleanup between the two starts.
        try (ConfigurableApplicationContext context = boot()) {
            log.info("first start");
        }
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        try (ConfigurableApplicationContext context = boot("--spring.profiles.active=local")) {
            log.info("second start");
        }
        assertThat(line(output, "first start")).startsWith("{");
        assertThat(line(output, "second start")).doesNotStartWith("{");
    }

    private static Map<String, Object> parse(String line) {
        return new JsonMapper().readValue(line, Map.class);
    }

    private static String line(CapturedOutput output, String marker) {
        return output.getOut().lines().filter(l -> l.contains(marker)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no line with " + marker));
    }
}
