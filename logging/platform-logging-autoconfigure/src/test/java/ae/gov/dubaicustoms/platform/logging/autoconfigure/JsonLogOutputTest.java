package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AsyncAppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * End to end through a REAL SpringApplication boot: the spring.factories-registered EPP points
 * logging.config at logback-platform.xml, Boot configures logback, and stdout carries JSON.
 */
@ExtendWith(OutputCaptureExtension.class)
class JsonLogOutputTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    @AfterEach
    void resetLoggingSystem() {
        // Logback state is JVM-global; put back a default config so other tests' output
        // expectations are unaffected by this class.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
    }

    private ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=orders")
                .run(args);
    }

    @Test
    void logsJsonWithEcsIshFieldsAndCorrelation(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = boot()) {
            Logger log = LoggerFactory.getLogger("json.probe");
            try (AutoCloseable scope = RequestContext.open(
                    new CorrelationId("0123456789abcdef0123456789abcdef"), Map.of())) {
                log.info("probe message");
            }
        }
        String jsonLine = output.getOut().lines()
                .filter(line -> line.contains("probe message"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no probe line captured"));

        assertThat(jsonLine).startsWith("{");
        assertThat(jsonLine)
                .contains("\"@timestamp\":")
                .contains("\"level\":\"INFO\"")
                .contains("\"logger\":\"json.probe\"")
                .contains("\"message\":\"probe message\"")
                .contains("\"service\":\"orders\"")
                .contains("\"traceId\":\"0123456789abcdef0123456789abcdef\"")
                .doesNotContain("\"correlationId\"");
        // Renamed/dropped by fieldNames config; their presence would mean the resource drifted.
        assertThat(jsonLine).doesNotContain("\"logger_name\"").doesNotContain("\"level_value\"");
    }

    @Test
    void stackTracesLandInTheStackTraceField(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            LoggerFactory.getLogger("json.probe").error("kaboom", new IllegalStateException("cause"));
        }
        String jsonLine = output.getOut().lines()
                .filter(line -> line.contains("kaboom"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error line captured"));
        assertThat(jsonLine).contains("\"stack_trace\":").contains("IllegalStateException");
    }

    @Test
    void theOnlyRootAppenderIsASynchronousConsoleAppenderWithThePlatformEncoder() {
        // AC14: nothing is buffered in an async appender, so shutdown cannot drop the last lines.
        try (ConfigurableApplicationContext context = boot()) {
            Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
            List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
            ((ch.qos.logback.classic.Logger) root).iteratorForAppenders().forEachRemaining(appenders::add);
            assertThat(appenders).singleElement()
                    .isInstanceOfSatisfying(ConsoleAppender.class, console ->
                            assertThat(console.getEncoder()).isInstanceOf(PlatformJsonEncoder.class))
                    .isNotInstanceOf(AsyncAppenderBase.class);
        }
    }

    @Test
    void killSwitchRestoresBootsOwnLogging(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("--dc.platform.logging.enabled=false")) {
            LoggerFactory.getLogger("off.probe").info("kill switch probe");
        }
        String line = output.getOut().lines().filter(l -> l.contains("kill switch probe")).findFirst()
                .orElseThrow(() -> new AssertionError("no line captured"));
        assertThat(line).doesNotStartWith("{");
    }

    @Test
    void localProfileFallsBackToHumanReadableConsole(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("--spring.profiles.active=local")) {
            LoggerFactory.getLogger("console.probe").info("console probe");
        }
        String line = output.getOut().lines()
                .filter(l -> l.contains("console probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no console line captured"));
        assertThat(line).doesNotStartWith("{");
    }
}
