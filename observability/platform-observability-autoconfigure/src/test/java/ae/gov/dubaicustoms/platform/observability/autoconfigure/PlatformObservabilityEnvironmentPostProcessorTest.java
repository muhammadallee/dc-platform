package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
                .isEqualTo("health,info,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState");
        assertThat(environment.getProperty("management.endpoint.health.group.dependencies.include"))
                .isEqualTo("db,rabbit");
        // ND13: actuator on its own port.
        assertThat(environment.getProperty("management.server.port")).isEqualTo("8081");
        assertThat(environment.getProperty("management.endpoint.health.validate-group-membership"))
                .isEqualTo("false");
        // HD5: no custom correlation header is mirrored into baggage; traceparent is the only carrier.
        assertThat(environment.getProperty("management.tracing.baggage.remote-fields")).isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
    }

    @Test
    void aRandomServerPortGivesARandomManagementPort() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "0");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.server.port")).isEqualTo("0");
    }

    @Test
    void anApplicationPortOnThePlatformsManagementPortFailsStartup() {
        // Codex phase 3 round 1, finding 1: server.port=8081 alone would put actuator on the application port.
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8081");

        assertThatIllegalStateException().isThrownBy(() -> postProcessor.postProcessEnvironment(environment, null))
                .withMessageContaining("server.port is 8081").withMessageContaining("management.server.port");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"08081", "+8081", " 8081 ", "0x1f91", "#1f91", "8 081"})
    void portsAreComparedAsNumbers(String spelling) {
        // Codex phase 3 round 1 reassessment, A: Boot compares the ports as numbers.
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", spelling);

        assertThatIllegalStateException().isThrownBy(() -> postProcessor.postProcessEnvironment(environment, null))
                .withMessageContaining("server.port is 8081");
    }

    @Test
    void aTypedPortValueIsBoundAsBootBindsIt() {
        // Verification 2, A: an unquoted YAML 8081.0 is a number; Boot binds it to 8081.
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("yaml",
                java.util.Map.of("server.port", 8081.0)));

        assertThatIllegalStateException().isThrownBy(() -> postProcessor.postProcessEnvironment(environment, null))
                .withMessageContaining("server.port is 8081");
    }

    @Test
    void anExplicitSamePortIsTheServicesChoice() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8081")
                .withProperty("management.server.port", "8081");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.server.port")).isEqualTo("8081");
    }

    @Test
    void aServicesOwnManagementPortWins() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "9090");
        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.server.port")).isEqualTo("9090");
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
        assertThat(environment.getProperty("management.endpoint.health.group.dependencies.include")).isNull();
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isNull();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
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
