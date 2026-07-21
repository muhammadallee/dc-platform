package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Proves the platform resilience defaults are contributed at lowest precedence and are overridable. */
class PlatformResilienceEnvironmentPostProcessorTest {

    private final PlatformResilienceEnvironmentPostProcessor processor =
            new PlatformResilienceEnvironmentPostProcessor();

    @Test
    void contributesResilience4jInstanceDefaults() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("resilience4j.retry.configs.default.max-attempts")).isEqualTo("3");
        assertThat(environment.getProperty("resilience4j.retry.configs.default.enable-exponential-backoff"))
                .isEqualTo("true");
        assertThat(environment.getProperty("resilience4j.circuitbreaker.configs.default.failure-rate-threshold"))
                .isEqualTo("50.0");
        assertThat(environment.getProperty("resilience4j.circuitbreaker.configs.default.sliding-window-size"))
                .isEqualTo("10");
        assertThat(environment.getProperty("resilience4j.timelimiter.configs.default.timeout-duration"))
                .isEqualTo("5s");
    }

    @Test
    void userConfigurationWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("user",
                Map.of("resilience4j.retry.configs.default.max-attempts", "7")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("resilience4j.retry.configs.default.max-attempts")).isEqualTo("7");
    }

    @Test
    void skipsWhenCapabilityDisabled() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("user",
                Map.of("dc.platform.resilience.enabled", "false")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("resilience4j.retry.configs.default.max-attempts")).isNull();
    }
}
