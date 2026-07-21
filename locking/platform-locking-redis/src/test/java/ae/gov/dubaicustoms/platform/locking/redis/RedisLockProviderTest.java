package ae.gov.dubaicustoms.platform.locking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Command-level unit tests: SET NX PX to acquire, token-fenced Lua to release. No Docker. */
class RedisLockProviderTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RedisLockProvider provider = new RedisLockProvider(redis);

    @Test
    void acquiresWhenSetIfAbsentSucceedsAndReleasesWithFencedScript() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("job"), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);

        Optional<LockHandle> handle = provider.tryAcquire("job", Duration.ofSeconds(30));
        assertThat(handle).isPresent();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(eq("job"), token.capture(), eq(Duration.ofSeconds(30)));

        handle.orElseThrow().close();
        verify(redis).execute(any(RedisScript.class), eq(List.of("job")), eq(token.getValue()));

        // Idempotent: a second close runs nothing further.
        handle.orElseThrow().close();
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of("job")), eq(token.getValue()));
    }

    @Test
    void emptyWhenAlreadyHeld() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void emptyWhenSetIfAbsentReturnsNull() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void releaseFailureIsSwallowed() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new IllegalStateException("redis down"));

        LockHandle handle = provider.tryAcquire("job", Duration.ofSeconds(30)).orElseThrow();

        assertThatCode(handle::close).doesNotThrowAnyException();
    }
}
