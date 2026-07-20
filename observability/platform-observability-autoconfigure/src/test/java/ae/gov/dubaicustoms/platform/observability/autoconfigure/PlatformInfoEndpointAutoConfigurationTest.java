package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.observability.autoconfigure.internal.PlatformEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ContextRunner matrix for {@link PlatformInfoEndpointAutoConfiguration}. */
class PlatformInfoEndpointAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformInfoEndpointAutoConfiguration.class))
            // ContextRunner skips EnvironmentPostProcessors, so expose the endpoint explicitly.
            .withPropertyValues("management.endpoints.web.exposure.include=platform");

    @Test
    void activeByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PlatformEndpoint.class);
            assertThat(ctx).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.observability.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(PlatformEndpoint.class);
                    assertThat(ctx).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void endpointToggleDisablesOnlyTheEndpoint() {
        runner.withPropertyValues("dc.platform.observability.platform-endpoint.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(PlatformEndpoint.class);
                    assertThat(ctx).hasSingleBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        PlatformEndpoint mine = new PlatformEndpoint(null);
        runner.withBean("myPlatformEndpoint", PlatformEndpoint.class, () -> mine)
                .run(ctx -> assertThat(ctx).getBean(PlatformEndpoint.class).isSameAs(mine));
    }

    @Test
    void inactiveWhenActuatorMissing() {
        runner.withClassLoader(new FilteredClassLoader(Endpoint.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PlatformInfoEndpointAutoConfiguration.class));
    }

    @Test
    void notCreatedWhenEndpointIsNotExposed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformInfoEndpointAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PlatformEndpoint.class));
    }

    @Test
    void descriptorReportsTheExportPosture() {
        runner.run(ctx -> assertThat(ctx.getBean(CapabilityDescriptor.class).detail())
                .isEqualTo("prometheus"));
        runner.withPropertyValues("dc.platform.observability.otlp.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(CapabilityDescriptor.class).detail())
                        .isEqualTo("prometheus+otlp"));
    }
}
