package ae.gov.dubaicustoms.platform.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Command-level behavior for the Redis provider against a mocked template (no Docker). */
class RedisRateLimiterProviderTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisRateLimiterProvider provider = new RedisRateLimiterProvider(redis);

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void allowsWhenCountWithinLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn((List) List.of(3L, 800L));

        Decision decision = provider.tryAcquire("k", 5, Duration.ofSeconds(1));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deniesWithTtlAsRetryAfterWhenOverLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn((List) List.of(6L, 750L));

        Decision decision = provider.tryAcquire("k", 5, Duration.ofSeconds(1));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failsOpenWhenRedisUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));

        Decision decision = provider.tryAcquire("k", 5, Duration.ofSeconds(1));

        assertThat(decision.allowed()).isTrue();
    }
}
