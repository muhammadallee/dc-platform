package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class PlatformDataJpaEnvironmentPostProcessorTest {

    private final PlatformDataJpaEnvironmentPostProcessor processor =
            new PlatformDataJpaEnvironmentPostProcessor();

    @Test
    void contributesLowestPrecedenceHibernateDefaults() {
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("50");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(environment.getPropertySources()
                .contains(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
    }

    @Test
    void userValueWins() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "true");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("true");
    }

    @Test
    void skipsWhenCapabilityDisabled() {
        MockEnvironment environment = new MockEnvironment().withProperty("dc.platform.data.jpa.enabled", "false");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()
                .contains(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
