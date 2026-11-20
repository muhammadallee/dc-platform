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
    void formatsAnEarlierPlatformStartLeftBehindAreClearedWhateverComesNext() {
        // Codex phase 3 round 1, findings 18 and J: Boot never clears the system property it copied the format into.
        String[] names = {"CONSOLE_LOG_STRUCTURED_FORMAT", "FILE_LOG_STRUCTURED_FORMAT",
            PlatformLoggingEnvironmentPostProcessor.OWNED_FORMAT_PROPERTIES};
        String[] previous = {System.getProperty(names[0]), System.getProperty(names[1]), System.getProperty(names[2])};
        try {
            for (String name : names) {
                System.clearProperty(name);
            }
            // A JSON start: Boot will copy the platform's format into both properties.
            postProcessor.postProcessEnvironment(new MockEnvironment(), null);
            System.setProperty(names[0], FORMATTER);
            System.setProperty(names[1], FORMATTER);
            // The next start picks ECS: both left-behind properties are cleared, so Boot copies ECS in.
            postProcessor.postProcessEnvironment(new MockEnvironment().withProperty(CONSOLE_FORMAT, "ecs"), null);
            assertThat(System.getProperty(names[0])).isNull();
            assertThat(System.getProperty(names[1])).isNull();
            // A property the service set itself, before any start, is never cleared.
            System.setProperty(names[0], "gelf");
            System.clearProperty(names[2]);
            postProcessor.postProcessEnvironment(new MockEnvironment(), null);
            postProcessor.postProcessEnvironment(new MockEnvironment().withProperty("dc.platform.logging.format",
                    "console"), null);
            assertThat(System.getProperty(names[0])).isEqualTo("gelf");
        } finally {
            for (int i = 0; i < names.length; i++) {
                restore(names[i], previous[i]);
            }
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
