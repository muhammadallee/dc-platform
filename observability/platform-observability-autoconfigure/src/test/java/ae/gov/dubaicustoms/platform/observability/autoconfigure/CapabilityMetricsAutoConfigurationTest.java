package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.observability.autoconfigure.internal.CapabilityGaugeRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CapabilityMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CapabilityMetricsAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class)
            .withBean("messagingDescriptor", CapabilityDescriptor.class,
                    () -> new CapabilityDescriptor("messaging", "ACTIVE", "inmemory"))
            .withBean("cacheDescriptor", CapabilityDescriptor.class,
                    () -> new CapabilityDescriptor("cache", "INACTIVE", ""));

    @Test
    void registersAGaugePerCapabilityWithActiveFlag() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CapabilityGaugeRegistrar.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find(CapabilityGaugeRegistrar.METRIC).tag("capability", "messaging").gauge().value())
                    .isEqualTo(1.0);
            assertThat(registry.find(CapabilityGaugeRegistrar.METRIC).tag("capability", "cache").gauge().value())
                    .isEqualTo(0.0);
        });
    }

    @Test
    void subToggleDisables() {
        runner.withPropertyValues("dc.platform.observability.capability-metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CapabilityGaugeRegistrar.class));
    }

    @Test
    void capabilityKillSwitchDisables() {
        runner.withPropertyValues("dc.platform.observability.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CapabilityGaugeRegistrar.class));
    }

    @Test
    void backsOffWhenUserRegistrarPresent() {
        CapabilityGaugeRegistrar mine = new CapabilityGaugeRegistrar(new SimpleMeterRegistry(), emptyProvider());
        runner.withBean("mine", CapabilityGaugeRegistrar.class, () -> mine)
                .run(context -> assertThat(context).getBean(CapabilityGaugeRegistrar.class).isSameAs(mine));
    }

    @Test
    void inactiveWhenMeterRegistryClassMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CapabilityMetricsAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(CapabilityGaugeRegistrar.class));
    }

    /** An {@link ObjectProvider} yielding no descriptors, so the back-off user bean is inert. */
    private static ObjectProvider<CapabilityDescriptor> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public CapabilityDescriptor getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityDescriptor getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityDescriptor getIfAvailable() {
                return null;
            }

            @Override
            public CapabilityDescriptor getIfUnique() {
                return null;
            }

            @Override
            public Stream<CapabilityDescriptor> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<CapabilityDescriptor> orderedStream() {
                return Stream.empty();
            }
        };
    }
}
