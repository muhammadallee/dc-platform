package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.logging.Kv;
import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase-02 R1–R3 through a real boot of logback-platform.xml: masking in every field (message, MDC,
 * structured arguments, exception message, stack trace), LogSanitizer beans applied, strict MDC mode,
 * and injection-safe lines.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedactedJsonLogOutputTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log = LoggerFactory.getLogger("redaction.probe");

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
    static class Empty {
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
    static class WithSanitizer {

        @Bean
        LogSanitizer customerNameSanitizer() {
            return (key, value) -> "customerName".equals(key) ? "C***" : value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(LoggingAutoConfiguration.class)
    static class WithAppendingSanitizer {

        @Bean
        LogSanitizer appendingSanitizer() {
            return (key, value) -> "note".equals(key) ? value + "!" : value;
        }
    }

    @AfterEach
    void reset() {
        MDC.clear();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
    }

    private ConfigurableApplicationContext boot(Class<?> config, String... args) {
        return new SpringApplicationBuilder(config).web(WebApplicationType.NONE)
                .properties("spring.application.name=orders").run(args);
    }

    @Test
    void masksEveryFieldTheEncoderWrites(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class)) {
            MDC.put("authorization", "Bearer abcdefgh12345678");
            MDC.put("payer", "card 4111111111111111");
            MDC.put("contact", "fatima@customs.gov.ae, +971 50 123 4567");
            log.error("login for {} with password=hunter2 token {}", kv("emiratesId", "784-1990-1234567-1"), JWT,
                    new IllegalStateException("rejected Bearer abcdefgh12345678"));
        }
        String line = line(output, "login for");
        JsonNode event = JSON.readTree(line);
        assertThat(line).doesNotContain(JWT).doesNotContain("hunter2").doesNotContain("abcdefgh12345678")
                .doesNotContain("4111111111111111").doesNotContain("1234567");
        assertThat(event.get("message").asText()).contains("password=****").contains("token ****");
        assertThat(event.get("authorization").asText()).isEqualTo("****");
        assertThat(event.get("payer").asText()).isEqualTo("card ****");
        assertThat(event.get("contact").asText()).isEqualTo("****, ****");
        assertThat(event.get("emiratesId").asText()).isEqualTo("****");
        assertThat(event.get("stack_trace").asText()).contains("IllegalStateException").contains("Bearer ****");
    }

    @Test
    void cardNumbersLoggedAsNumbersAreMasked(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class)) {
            log.info("numeric {} {}", kv("card", 4111111111111111L), kv("orderId", 1793001600123L));
        }
        String line = line(output, "numeric");
        JsonNode event = JSON.readTree(line);
        assertThat(line).doesNotContain("4111111111111111");
        assertThat(event.get("card").asText()).isEqualTo("****");
        assertThat(event.get("orderId").asLong()).isEqualTo(1793001600123L);
    }

    @Test
    void ordinaryValuesAreNotMasked(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class)) {
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("orderId", "400000000000001");
            log.info("took {} ms at {}", 250, 1793001600123L);
        }
        JsonNode event = JSON.readTree(line(output, "took 250 ms"));
        assertThat(event.get("message").asText()).isEqualTo("took 250 ms at 1793001600123");
        assertThat(event.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(event.get("orderId").asText()).isEqualTo("400000000000001");
    }

    @Test
    void appliesLogSanitizerBeansToMdcAndArguments(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(WithSanitizer.class)) {
            assertThat(context.getBeanNamesForType(
                    ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry.class)).hasSize(1);
            MDC.put("customerName", "Fatima");
            log.info("hello {} and {}", kv("customerName", "Fatima"), Kv.of("customerName", "Fatima"));
            log.info("unrelated {}", kv("city", "Dubai"));
        }
        String line = line(output, "hello");
        JsonNode event = JSON.readTree(line);
        assertThat(event.get("customerName").asText()).isEqualTo("C***");
        // Round 1 F5: the formatted message no longer carries the raw value either.
        assertThat(event.get("message").asText()).isEqualTo("hello customerName=C*** and customerName=C***");
        assertThat(line).doesNotContain("Fatima");
        assertThat(JSON.readTree(line(output, "unrelated")).get("message").asText()).isEqualTo("unrelated city=Dubai");
    }

    @Test
    void aNonIdempotentSanitizerRunsOncePerOutput(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(WithAppendingSanitizer.class)) {
            log.info("note {}", kv("note", "x"));
        }
        JsonNode event = JSON.readTree(line(output, "note"));
        assertThat(event.get("note").asText()).isEqualTo("x!");
        assertThat(event.get("message").asText()).isEqualTo("note note=x!");
    }

    @Test
    void redactionCanBeSwitchedOffAndExtended(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class, "--dc.platform.logging.redaction.enabled=false")) {
            assertThat(context.getBeanNamesForType(
                    ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry.class)).isEmpty();
            log.info("off password=hunter2");
        }
        assertThat(line(output, "off password")).contains("password=hunter2");
        reset();
        try (ConfigurableApplicationContext context = boot(Empty.class,
                "--dc.platform.logging.redaction.patterns[0]=CASE-\\d{6}")) {
            log.info("extended CASE-123456");
        }
        assertThat(JSON.readTree(line(output, "extended")).get("message").asText()).isEqualTo("extended ****");
    }

    @Test
    void strictMdcModeWritesOnlyPlatformAndListedKeys(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class, "--dc.platform.logging.mdc.include=tenant")) {
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("spanId", "00f067aa0ba902b7");
            MDC.put("tenant", "dubai");
            MDC.put("internalDebug", "x");
            log.info("strict probe");
        }
        JsonNode event = JSON.readTree(line(output, "strict probe"));
        assertThat(event.has("traceId")).isTrue();
        assertThat(event.has("spanId")).isTrue();
        assertThat(event.get("tenant").asText()).isEqualTo("dubai");
        assertThat(event.has("internalDebug")).isFalse();
    }

    @Test
    void consumerMdcPassesThroughByDefault(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot(Empty.class)) {
            MDC.put("internalDebug", "x");
            log.info("default mdc probe");
        }
        assertThat(JSON.readTree(line(output, "default mdc probe")).get("internalDebug").asText()).isEqualTo("x");
    }

    @Test
    void userInputCannotForgeLinesOrFields(CapturedOutput output) throws Exception {
        String hostile = "a\r\nb\"c {\"level\":\"ERROR\",\"message\":\"forged\"}";
        try (ConfigurableApplicationContext context = boot(Empty.class)) {
            MDC.put("input", hostile);
            log.info("injection probe {}", hostile);
        }
        List<String> lines = output.getOut().lines().filter(l -> l.contains("injection probe")).toList();
        assertThat(lines).hasSize(1);
        assertThat(output.getOut().lines().filter(l -> l.startsWith("{\"level\":\"ERROR\""))).isEmpty();
        JsonNode event = JSON.readTree(lines.get(0));
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("message").asText()).isEqualTo("injection probe " + hostile);
        assertThat(event.get("input").asText()).isEqualTo(hostile);
    }

    private static String line(CapturedOutput output, String marker) {
        return output.getOut().lines().filter(l -> l.contains(marker)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no line with " + marker));
    }
}
