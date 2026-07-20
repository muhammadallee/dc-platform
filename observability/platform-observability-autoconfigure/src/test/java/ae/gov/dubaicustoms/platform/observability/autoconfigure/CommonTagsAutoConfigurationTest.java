package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ContextRunner matrix + tag behavior for {@link CommonTagsAutoConfiguration}. */
class CommonTagsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonTagsAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(ctx -> assertThat(ctx).hasBean("platformCommonTagsCustomizer"));
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.observability.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformCommonTagsCustomizer"));
    }

    @Test
    void commonTagsToggleDisables() {
        runner.withPropertyValues("dc.platform.observability.common-tags.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformCommonTagsCustomizer"));
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        MeterRegistryCustomizer<MeterRegistry> mine = registry -> { };
        runner.withBean("platformCommonTagsCustomizer", MeterRegistryCustomizer.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean("platformCommonTagsCustomizer")).isSameAs(mine));
    }

    @Test
    void inactiveWhenMicrometerMissing() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CommonTagsAutoConfiguration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stampsServiceEnvAndPlatformVersionOnEveryMeter() {
        runner.withPropertyValues("spring.application.name=orders", "spring.profiles.active=sit,extra")
                .run(ctx -> {
                    MeterRegistryCustomizer<MeterRegistry> customizer =
                            ctx.getBean("platformCommonTagsCustomizer", MeterRegistryCustomizer.class);
                    SimpleMeterRegistry registry = new SimpleMeterRegistry();
                    customizer.customize(registry);
                    Counter counter = registry.counter("dc.platform.test");
                    assertThat(counter.getId().getTag("service")).isEqualTo("orders");
                    assertThat(counter.getId().getTag("env")).isEqualTo("sit");
                    assertThat(counter.getId().getTag("platform.version")).isNotBlank();
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToDefaultsWithoutNameAndProfiles() {
        runner.run(ctx -> {
            MeterRegistryCustomizer<MeterRegistry> customizer =
                    ctx.getBean("platformCommonTagsCustomizer", MeterRegistryCustomizer.class);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            customizer.customize(registry);
            Counter counter = registry.counter("dc.platform.test");
            assertThat(counter.getId().getTag("service")).isEqualTo("application");
            assertThat(counter.getId().getTag("env")).isEqualTo("default");
        });
    }
}
