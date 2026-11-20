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
        String console = "CONSOLE_LOG_STRUCTURED_FORMAT";
        String file = "FILE_LOG_STRUCTURED_FORMAT";
        String[] names = {console, file, PlatformLoggingEnvironmentPostProcessor.OWNED_PREFIX + console,
            PlatformLoggingEnvironmentPostProcessor.OWNED_PREFIX + file, "user.timezone.probe"};
        String[] previous = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            previous[i] = System.getProperty(names[i]);
            System.clearProperty(names[i]);
        }
        try {
            // A JSON start; Boot then copies the platform's format into both properties.
            postProcessor.postProcessEnvironment(new MockEnvironment(), null);
            System.setProperty(console, FORMATTER);
            System.setProperty(file, FORMATTER);
            // The next start picks ECS: both left-behind values are cleared, so Boot copies ECS in.
            postProcessor.postProcessEnvironment(new MockEnvironment().withProperty(CONSOLE_FORMAT, "ecs"), null);
            assertThat(System.getProperty(console)).isNull();
            assertThat(System.getProperty(file)).isNull();
            // A value the service set after a platform start is its own: never cleared.
            System.setProperty(console, FORMATTER);
            postProcessor.postProcessEnvironment(new MockEnvironment(), null);
            System.setProperty(console, "gelf");
            postProcessor.postProcessEnvironment(new MockEnvironment().withProperty("dc.platform.logging.format",
                    "console"), null);
            assertThat(System.getProperty(console)).isEqualTo("gelf");
            // Only the two bridge properties are ever cleared.
            System.setProperty(names[4], "kept");
            System.setProperty(PlatformLoggingEnvironmentPostProcessor.OWNED_PREFIX + names[4], "kept");
            postProcessor.postProcessEnvironment(new MockEnvironment(), null);
            assertThat(System.getProperty(names[4])).isEqualTo("kept");
        } finally {
            System.clearProperty(PlatformLoggingEnvironmentPostProcessor.OWNED_PREFIX + names[4]);
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
