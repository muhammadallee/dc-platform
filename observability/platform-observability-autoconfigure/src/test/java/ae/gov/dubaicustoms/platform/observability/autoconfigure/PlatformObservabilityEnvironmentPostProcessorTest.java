package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Default-contribution and precedence behavior of the observability EnvironmentPostProcessor. */
class PlatformObservabilityEnvironmentPostProcessorTest {

    private final PlatformObservabilityEnvironmentPostProcessor postProcessor =
            new PlatformObservabilityEnvironmentPostProcessor();

    @Test
    void contributesManagementDefaultsAtLowestPrecedence() {
        MockEnvironment environment = new MockEnvironment();
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(PlatformObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,platform,metrics,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,rabbit,redis");
        assertThat(environment.getProperty("management.endpoint.health.validate-group-membership"))
                .isEqualTo("false");
        assertThat(environment.getProperty("management.tracing.baggage.remote-fields"))
                .isEqualTo("X-Correlation-Id");
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
    }

    @Test
    void killSwitchContributesNothing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.observability.enabled", "false");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(PlatformObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void healthGroupsToggleDropsOnlyTheGroupKeys() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.observability.health.groups.enabled", "false");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include")).isNull();
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isNull();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,platform,metrics,prometheus");
    }

    @Test
    void otlpOnLeavesExportConfigurationToBoot() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.observability.otlp.enabled", "true");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isNull();
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled")).isNull();
    }

    @Test
    void userConfigurationAlwaysWins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "health");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
    }
}
