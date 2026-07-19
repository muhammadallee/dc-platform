package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Pre-context behavior: what the EPP contributes, and when it stays out of the way. */
class PlatformLoggingEnvironmentPostProcessorTest {

    private final PlatformLoggingEnvironmentPostProcessor postProcessor =
            new PlatformLoggingEnvironmentPostProcessor();

    @Test
    void defaultsLoggingConfigToThePlatformXml() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.config"))
                .isEqualTo(PlatformLoggingEnvironmentPostProcessor.CONFIG_LOCATION);
        assertThat(environment.getPropertySources()
                .contains(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
    }

    @Test
    void serviceNameDefaultsToSpringApplicationName() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "orders");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dc.platform.logging.service-name")).isEqualTo("orders");
    }

    @Test
    void serviceNameFallsBackToApplicationWhenUnnamed() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dc.platform.logging.service-name")).isEqualTo("application");
    }

    @Test
    void explicitConsoleFormatContributesNothing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.format", "console");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.config")).isNull();
    }

    @Test
    void localProfileFallsBackToConsole() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.config")).isNull();
    }

    @Test
    void explicitJsonBeatsTheLocalProfileFallback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.format", "json");
        environment.setActiveProfiles("local");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.config"))
                .isEqualTo(PlatformLoggingEnvironmentPostProcessor.CONFIG_LOCATION);
    }

    @Test
    void userLoggingConfigAlwaysWins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.config", "classpath:my-logback.xml");

        postProcessor.postProcessEnvironment(environment, null);

        // Ours went in addLast; the user's source outranks it.
        assertThat(environment.getProperty("logging.config")).isEqualTo("classpath:my-logback.xml");
    }

    @Test
    void killSwitchDisablesEntirely() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.enabled", "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
