package ae.gov.dubaicustoms.platform.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the Redis provider against a real Redis: a fixed window of N permits allows exactly N and
 * then denies. Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
class RedisRateLimiterProviderIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisRateLimiterProvider provider;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        provider = new RedisRateLimiterProvider(redis);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void allowsExactlyThePermitsThenDenies() {
        Duration window = Duration.ofMinutes(1);

        for (int i = 0; i < 3; i++) {
            assertThat(provider.tryAcquire("job", 3, window).allowed())
                    .as("request %d within limit", i + 1).isTrue();
        }

        assertThat(provider.tryAcquire("job", 3, window).allowed()).isFalse();
    }
}
