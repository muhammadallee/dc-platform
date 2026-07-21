package ae.gov.dubaicustoms.platform.redis.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the key prefix reaches the wire against a real Redis: a value written at logical key
 * {@code "k"} is stored under {@code "<app>:k"}. Excluded from the default build (docker JUnit tag);
 * run under {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
class RedisPrefixRoundTripIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, PlatformRedisAutoConfiguration.class))
            .withPropertyValues(
                    "spring.application.name=svc",
                    "spring.data.redis.host=" + REDIS.getHost(),
                    "spring.data.redis.port=" + REDIS.getMappedPort(6379));

    @Test
    void keysAreStoredUnderTheApplicationPrefix() {
        runner.run(context -> {
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
            template.opsForValue().set("k", "v");

            // A raw (unprefixed) template sees the physical key namespaced under "svc:".
            StringRedisTemplate raw = new StringRedisTemplate(context.getBean(RedisConnectionFactory.class));
            raw.afterPropertiesSet();
            assertThat(raw.opsForValue().get("svc:k")).isEqualTo("v");
            assertThat(raw.opsForValue().get("k")).isNull();

            // And the prefixing template reads its own logical key back.
            assertThat(template.opsForValue().get("k")).isEqualTo("v");
        });
    }
}
