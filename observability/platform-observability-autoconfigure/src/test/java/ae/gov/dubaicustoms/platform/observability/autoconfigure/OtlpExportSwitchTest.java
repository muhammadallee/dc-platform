package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Phase-02 round 1 F7 and AC16 with the observability starter's tracing classpath (bridge, Boot
 * companion module, OTLP exporter), through a real boot so the EnvironmentPostProcessor runs: the
 * OTLP span exporter exists only when dc.platform.observability.otlp.enabled=true, and head sampling
 * stays Boot's default 0.1 (D103).
 */
class OtlpExportSwitchTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).run(args);
    }

    @Test
    void spanExportIsOffByDefault() {
        try (ConfigurableApplicationContext context = boot()) {
            assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isEmpty();
        }
    }

    @Test
    void theSwitchTurnsOnTheOtlpSpanExporter() {
        // Points at a closed local port: the exporter is created lazily and nothing is sent here.
        try (ConfigurableApplicationContext context = boot("--dc.platform.observability.otlp.enabled=true",
                "--management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces")) {
            assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).hasSize(1);
        }
    }

    @Test
    void headSamplingStaysBootsDefault() {
        try (ConfigurableApplicationContext context = boot()) {
            assertThat(context.getBean(TracingProperties.class).getSampling().getProbability()).isEqualTo(0.1f);
        }
    }
}
