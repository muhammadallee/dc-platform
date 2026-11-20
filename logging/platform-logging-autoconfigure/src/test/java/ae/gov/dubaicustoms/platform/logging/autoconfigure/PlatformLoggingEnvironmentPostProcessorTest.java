package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformLogstashFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** Pre-context behaviour: which structured-logging defaults the EPP contributes, and when it stays out of the way. */
class PlatformLoggingEnvironmentPostProcessorTest {

    private static final String CONSOLE_FORMAT = "logging.structured.format.console";
    private static final String FORMATTER = PlatformLogstashFormatter.class.getName();

    private final PlatformLoggingEnvironmentPostProcessor postProcessor =
            new PlatformLoggingEnvironmentPostProcessor();

    @Test
    void defaultsToBootsStructuredLoggingInTheLogstashFormat() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(CONSOLE_FORMAT)).isEqualTo(FORMATTER);
        assertThat(environment.getProperty("logging.structured.format.file")).isEqualTo(FORMATTER);
        assertThat(environment.getProperty("logging.config")).isNull();
        assertThat(environment.getPropertySources()
                .contains(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
    }

    @Test
    void explicitConsoleFormatContributesNothing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.format", "console");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(CONSOLE_FORMAT)).isNull();
    }

    @Test
    void userBannerModeWins() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.main.banner-mode", "console");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("console");
    }

    @Test
    void localProfileFallsBackToConsole() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(CONSOLE_FORMAT)).isNull();
    }

    @Test
    void explicitJsonBeatsTheLocalProfileFallback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.format", "json");
        environment.setActiveProfiles("local");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(CONSOLE_FORMAT)).isEqualTo(FORMATTER);
    }

    @Test
    void aFormatThePlatformSetInAnEarlierStartIsClearedForConsoleLogging() {
        // Codex phase 3 round 1, finding 18: Boot never clears the system property it copied the format into.
        String previousConsole = System.getProperty("CONSOLE_LOG_STRUCTURED_FORMAT");
        String previousFile = System.getProperty("FILE_LOG_STRUCTURED_FORMAT");
        try {
            System.setProperty("CONSOLE_LOG_STRUCTURED_FORMAT", FORMATTER);
            System.setProperty("FILE_LOG_STRUCTURED_FORMAT", "ecs");
            MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.logging.format", "console");

            postProcessor.postProcessEnvironment(environment, null);

            assertThat(System.getProperty("CONSOLE_LOG_STRUCTURED_FORMAT")).isNull();
            assertThat(System.getProperty("FILE_LOG_STRUCTURED_FORMAT")).as("not the platform's: kept").isEqualTo("ecs");
        } finally {
            restore("CONSOLE_LOG_STRUCTURED_FORMAT", previousConsole);
            restore("FILE_LOG_STRUCTURED_FORMAT", previousFile);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void userStructuredLoggingValuesAlwaysWin() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(CONSOLE_FORMAT, "ecs")
                .withProperty("logging.structured.json.exclude", "tags");

        postProcessor.postProcessEnvironment(environment, null);

        // Ours went in addLast; the user's source outranks it.
        assertThat(environment.getProperty(CONSOLE_FORMAT)).isEqualTo("ecs");
        assertThat(environment.getProperty("logging.structured.json.exclude")).isEqualTo("tags");
    }

    @Test
    void includeMdcFalseLeavesTheContextOut() {
        MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.logging.include-mdc", "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.structured.json.context.include")).isEqualTo("false");
    }

    @Test
    void killSwitchDisablesEntirely() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dc.platform.logging.enabled", "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void contributedDefaultsSnapshot() {
        // Plan §7.2 for the pre-context defaults: a changed default fails here until updated on purpose.
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "orders");
        postProcessor.postProcessEnvironment(environment, null);

        MapPropertySource defaults = (MapPropertySource) environment.getPropertySources()
                .get(PlatformLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(CONSOLE_FORMAT, FORMATTER);
        expected.put("logging.structured.format.file", FORMATTER);
        expected.put("logging.structured.json.context.include", "true");
        expected.put("spring.main.banner-mode", "off");
        assertThat(defaults.getSource()).containsExactlyEntriesOf(expected);
    }
}
