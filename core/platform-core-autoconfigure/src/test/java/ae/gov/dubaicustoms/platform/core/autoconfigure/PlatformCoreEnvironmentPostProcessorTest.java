package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class PlatformCoreEnvironmentPostProcessorTest {

    private final PlatformCoreEnvironmentPostProcessor processor = new PlatformCoreEnvironmentPostProcessor();

    @Test
    void turnsOnBootsTaskContextPropagationByDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("spring.task.execution.propagate-context")).isEqualTo("true");
        assertThat(environment.getPropertySources().contains(PlatformCoreEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isTrue();
    }

    @Test
    void applicationValuesWin() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app",
                Map.of("spring.task.execution.propagate-context", "false")));
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("spring.task.execution.propagate-context")).isEqualTo("false");
    }

    @Test
    void killSwitchContributesNothing() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of("dc.platform.core.enabled", "false")));
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getPropertySources().contains(PlatformCoreEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }
}
