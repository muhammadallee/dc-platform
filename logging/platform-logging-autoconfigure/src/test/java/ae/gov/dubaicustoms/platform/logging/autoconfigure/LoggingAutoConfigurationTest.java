package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.logging.Kv;
import org.junit.jupiter.api.Test;
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
}
