package ae.gov.dubaicustoms.platform.ratelimit.inmemory;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;

/**
 * Default {@link RateLimiterProvider}: a per-JVM <em>sliding-window counter</em> backed by Caffeine.
 * For each key it keeps the count in the current fixed window and the previous window's total, and
 * estimates the rolling count as {@code current + previous * (fraction of the window still overlapping
 * the past window)}. This smooths the burst-at-window-boundary flaw of a plain fixed window while
 * costing one small entry per active key.
 *
 * <p><strong>Limitation:</strong> counters are local to this JVM — a service running N instances
 * allows up to N&times; the configured rate cluster-wide. Use the Redis provider when the limit must
 * be global. The autoconfigure logs a WARN about this in the {@code prod} profile.
 *
 * <p>Thread-safe: each key's counter is mutated under its own monitor; the cache is bounded and
 * auto-evicts idle keys.
 *
 * @since 0.2.0
 */
public final class InMemoryRateLimiterProvider implements RateLimiterProvider {

    private final Cache<String, Window> windows;
    private final Clock clock;

    /** Creates a provider using the system UTC clock and a bounded, idle-evicting bucket cache. */
    public InMemoryRateLimiterProvider() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock the clock driving window alignment; inject a fixed/mutable clock in tests
     */
    public InMemoryRateLimiterProvider(Clock clock) {
        this.clock = clock;
        this.windows = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    public Decision tryAcquire(String key, int permits, Duration window) {
        long windowMillis = Math.max(1, window.toMillis());
        long now = clock.millis();
        long currentStart = now - Math.floorMod(now, windowMillis);
        Window state = windows.get(key, k -> new Window());
        synchronized (state) {
            state.roll(currentStart, windowMillis);
            long elapsed = now - currentStart;
            double previousWeight = (double) (windowMillis - elapsed) / windowMillis;
            double estimated = state.previousCount * previousWeight + state.currentCount;
            if (estimated + 1 > permits) {
                // Retry once the current fixed window rolls over and the previous window's weight drops.
                return Decision.deny(Duration.ofMillis(windowMillis - elapsed));
            }
            state.currentCount++;
            return Decision.allow();
        }
    }

    /** Mutable per-key counter: the current window's start and count plus the previous window's total. */
    private static final class Window {
        private long currentStart = Long.MIN_VALUE;
        private long currentCount;
        private long previousCount;

        private void roll(long newStart, long windowMillis) {
            if (newStart == currentStart) {
                return;
            }
            if (newStart == currentStart + windowMillis) {
                // Advanced exactly one window: last window's count becomes the "previous" weight.
                previousCount = currentCount;
            } else {
                // Idle for more than a window: nothing carries over.
                previousCount = 0;
            }
            currentCount = 0;
            currentStart = newStart;
        }
    }
}
