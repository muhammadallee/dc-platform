package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class PlatformMessagingEnvironmentPostProcessorTest {

    private final PlatformMessagingEnvironmentPostProcessor processor = new PlatformMessagingEnvironmentPostProcessor();

    @Test
    void configDefaultSnapshot() {
        // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        MapPropertySource source = (MapPropertySource) environment.getPropertySources()
                .get(PlatformMessagingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(source).isNotNull();
        assertThat(source.getSource()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "spring.rabbitmq.publisher-confirm-type", "correlated",
                "spring.rabbitmq.publisher-returns", "true",
                "spring.rabbitmq.template.mandatory", "true",
                "spring.rabbitmq.connection-timeout", "5s"));
    }

    @Test
    void userValueWins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.rabbitmq.connection-timeout", "9s");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.rabbitmq.connection-timeout")).isEqualTo("9s");
    }

    @Test
    void skipsWhenCapabilityDisabled() {
        MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.messaging.enabled", "false");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()
                .contains(PlatformMessagingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
