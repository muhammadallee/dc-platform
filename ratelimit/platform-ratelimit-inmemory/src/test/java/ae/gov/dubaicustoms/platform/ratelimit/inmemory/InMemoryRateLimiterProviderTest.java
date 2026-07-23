package ae.gov.dubaicustoms.platform.ratelimit.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Sliding-window behavior for the in-memory provider, made deterministic with a mutable clock. */
class InMemoryRateLimiterProviderTest {

    // Aligned to a 1s window boundary (epoch millis divisible by 1000).
    private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_700_000_000_000L));
    private final InMemoryRateLimiterProvider provider = new InMemoryRateLimiterProvider(clock);

    @Test
    void allowsUpToPermitsThenDenies() {
        Duration window = Duration.ofSeconds(1);

        assertThat(provider.tryAcquire("k", 3, window).allowed()).isTrue();
        assertThat(provider.tryAcquire("k", 3, window).allowed()).isTrue();
        assertThat(provider.tryAcquire("k", 3, window).allowed()).isTrue();

        Decision denied = provider.tryAcquire("k", 3, window);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void keysAreIsolated() {
        Duration window = Duration.ofSeconds(1);
        assertThat(provider.tryAcquire("a", 1, window).allowed()).isTrue();
        assertThat(provider.tryAcquire("a", 1, window).allowed()).isFalse();

        // A different key has its own bucket.
        assertThat(provider.tryAcquire("b", 1, window).allowed()).isTrue();
    }

    @Test
    void allowsAgainAfterFullWindowElapses() {
        Duration window = Duration.ofSeconds(1);
        assertThat(provider.tryAcquire("k", 1, window).allowed()).isTrue();
        assertThat(provider.tryAcquire("k", 1, window).allowed()).isFalse();

        // Advance two full windows: no previous-window weight carries over, so the bucket is fresh.
        clock.advance(Duration.ofSeconds(2));
        assertThat(provider.tryAcquire("k", 1, window).allowed()).isTrue();
    }

    @Test
    void slidingEstimateStillBlocksEarlyInTheNextWindow() {
        Duration window = Duration.ofSeconds(1);
        // Saturate the first window.
        for (int i = 0; i < 10; i++) {
            provider.tryAcquire("k", 10, window);
        }
        // Cross into the next window: the previous window still weighs ~fully, so we stay blocked.
        clock.advance(Duration.ofSeconds(1));
        assertThat(provider.tryAcquire("k", 10, window).allowed()).isFalse();

        // Near the end of the new window the previous weight has decayed, so requests are allowed.
        clock.advance(Duration.ofMillis(980));
        assertThat(provider.tryAcquire("k", 10, window).allowed()).isTrue();
    }

    /** A Clock whose instant the test advances explicitly. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        private void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
