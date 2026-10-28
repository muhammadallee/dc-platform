package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
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
    void configDefaultSnapshot() {
        // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(contributed(environment)).containsExactlyEntriesOf(snapshot(
                "spring.jpa.open-in-view", "false",
                "spring.jpa.properties.hibernate.jdbc.batch_size", "50",
                "spring.jpa.properties.hibernate.order_inserts", "true",
                "spring.jpa.properties.hibernate.order_updates", "true",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC",
                "spring.datasource.hikari.connection-timeout", "5000",
                "spring.transaction.default-timeout", "30s",
                "spring.flyway.clean-disabled", "true",
                "spring.flyway.validate-on-migrate", "true",
                "spring.flyway.out-of-order", "false",
                "spring.flyway.validate-migration-naming", "true"));
    }

    @Test
    void contributesNoDatabaseSpecificKeys() {
        // Oracle driver timeouts are added to the pool itself (OracleConnectionDefaults), keyed on its URL.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:oracle:thin:@//db:1521/APP");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(contributed(environment).keySet()).noneMatch(key -> key.contains("oracle"));
    }

    @Test
    void leakDetectionOnlyUnderTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        processor.postProcessEnvironment(local, new SpringApplication());
        assertThat(contributed(local)).containsEntry("spring.datasource.hikari.leak-detection-threshold", "20000");

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        processor.postProcessEnvironment(prod, new SpringApplication());
        assertThat(contributed(prod)).doesNotContainKey("spring.datasource.hikari.leak-detection-threshold");
    }

    @Test
    void userPoolAndTransactionValuesWin() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.connection-timeout", "250")
                .withProperty("spring.transaction.default-timeout", "2m");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("250");
        assertThat(environment.getProperty("spring.transaction.default-timeout")).isEqualTo("2m");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contributed(MockEnvironment environment) {
        return ((MapPropertySource) environment.getPropertySources()
                .get(PlatformDataJpaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).getSource();
    }

    private static Map<String, Object> snapshot(String... keysAndValues) {
        Map<String, Object> expected = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            expected.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return expected;
    }
}
