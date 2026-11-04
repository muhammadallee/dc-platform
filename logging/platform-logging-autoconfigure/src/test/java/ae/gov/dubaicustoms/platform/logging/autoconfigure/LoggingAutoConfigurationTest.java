package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.logging.Kv;
import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The mandatory ContextRunner matrix for LoggingAutoConfiguration. */
class LoggingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
            assertThat(context.getBean(CapabilityDescriptor.class))
                    .isEqualTo(new CapabilityDescriptor("logging", "ACTIVE", "json"));
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.logging.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CapabilityDescriptor.class));
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        CapabilityDescriptor mine = new CapabilityDescriptor("logging", "ACTIVE", "custom");
        runner.withBean("loggingCapabilityDescriptor", CapabilityDescriptor.class, () -> mine)
                .run(context -> assertThat(context.getBean(CapabilityDescriptor.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenLoggingApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(Kv.class))
                .run(context -> assertThat(context).doesNotHaveBean(LoggingAutoConfiguration.class));
    }

    @Test
    void bannerReportsTheEffectiveConsoleFallback() {
        // Ordering/behavior case: the descriptor reflects the EPP's local-profile decision,
        // not the record default.
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context.getBean(CapabilityDescriptor.class).detail())
                        .isEqualTo("console"));
    }

    @Test
    void unknownFormatFailsStartup() {
        runner.withPropertyValues("dc.platform.logging.format=xml")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("xml"));
    }

    @Test
    void propertiesBindFromKebabKeys() {
        runner.withPropertyValues(
                        "dc.platform.logging.format=console",
                        "dc.platform.logging.include-mdc=false",
                        "dc.platform.logging.service-name=orders")
                .run(context -> {
                    LoggingProperties properties = context.getBean(LoggingProperties.class);
                    assertThat(properties.format()).isEqualTo(LoggingProperties.Format.CONSOLE);
                    assertThat(properties.includeMdc()).isFalse();
                    assertThat(properties.serviceName()).isEqualTo("orders");
                });
    }

    @Test
    void sanitizerRegistryActiveByDefaultAndOffWithRedaction() {
        runner.run(context -> assertThat(context).hasSingleBean(LogSanitizerRegistry.class));
        runner.withPropertyValues("dc.platform.logging.redaction.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LogSanitizerRegistry.class));
    }

    @Test
    void sanitizerRegistryBacksOffForAUserRegistryUnderAnyName() {
        LogSanitizerRegistry mine = new LogSanitizerRegistry(new DefaultListableBeanFactory().getBeanProvider(LogSanitizer.class));
        runner.withBean("myRegistry", LogSanitizerRegistry.class, () -> mine)
                .run(context -> assertThat(context.getBean(LogSanitizerRegistry.class)).isSameAs(mine));
    }

    @Test
    void unrelatedDescriptorsDoNotSuppressTheLoggingOne() {
        runner.withBean("otherCapabilityDescriptor", CapabilityDescriptor.class,
                        () -> new CapabilityDescriptor("other", "ACTIVE", ""))
                .run(context -> assertThat(context).hasBean("loggingCapabilityDescriptor"));
    }

    @Test
    void configurationDefaultsSnapshot() {
        // Plan §7.2: a changed default fails here until the snapshot is consciously updated.
        runner.run(context -> {
            LoggingProperties p = context.getBean(LoggingProperties.class);
            assertThat(Map.of("enabled", p.enabled(), "format", p.format(), "include-mdc", p.includeMdc(),
                    "service-name", p.serviceName(), "mdc.include", p.mdc().include(),
                    "redaction.enabled", p.redaction().enabled(), "redaction.patterns", p.redaction().patterns()))
                    .isEqualTo(Map.of("enabled", true, "format", LoggingProperties.Format.JSON, "include-mdc", true,
                            "service-name", "", "mdc.include", List.of(),
                            "redaction.enabled", true, "redaction.patterns", List.of()));
        });
    }

    @Test
    void platformCodeWritesOnlyTraceAndSpanIdsToTheMdc() throws IOException {
        // Phase-02 R2.1 (D101): the platform's own MDC emission is an allow-list. Every MDC.put in
        // platform main code is in RequestContext (key traceId plus the caller's extras), and the only
        // callers that open a context are the correlation filter, whose extras are spanId, and the outbox
        // drainer restoring a stored trace, with no extras.
        Path root = Path.of("../..").toAbsolutePath().normalize();
        List<Path> writers = mainSourcesContaining(root, "MDC.put(");
        assertThat(writers).extracting(f -> f.getFileName().toString()).containsExactly("RequestContext.java");
        assertThat(read(writers.get(0))).contains("CORRELATION_ID_KEY = \"traceId\"");
        List<Path> openers = mainSourcesContaining(root, "RequestContext.open(");
        assertThat(openers).extracting(f -> f.getFileName().toString())
                .containsExactlyInAnyOrder("CorrelationIdFilter.java", "PlatformOutboxContext.java");
        for (Path opener : openers) {
            String expectedExtras = opener.endsWith("CorrelationIdFilter.java")
                    ? "Map.of(\"spanId\", trace.spanId())" : "RequestContext.open(new CorrelationId(traceId), Map.of())";
            assertThat(read(opener)).contains(expectedExtras);
        }
    }

    private static List<Path> mainSourcesContaining(Path root, String needle) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(f -> !f.toString().replace('\\', '/').contains("/target/"))
                    .filter(f -> read(f).contains(needle))
                    .toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
