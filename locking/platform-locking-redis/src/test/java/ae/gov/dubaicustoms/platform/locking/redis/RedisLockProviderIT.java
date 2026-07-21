package ae.gov.dubaicustoms.platform.locking.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import java.time.Duration;
import java.util.Optional;
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
 * Proves the Redis provider against a real Redis: acquisition is mutually exclusive and release frees
 * the lock. Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@Testcontainers
class RedisLockProviderIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisLockProvider provider;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        provider = new RedisLockProvider(redis);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void acquisitionIsMutuallyExclusiveAndReleaseFrees() {
        Optional<LockHandle> first = provider.tryAcquire("job", Duration.ofMinutes(5));
        assertThat(first).isPresent();
        assertThat(provider.tryAcquire("job", Duration.ofMinutes(5))).isEmpty();

        first.orElseThrow().close();
        assertThat(provider.tryAcquire("job", Duration.ofMinutes(5))).isPresent();
    }
}
