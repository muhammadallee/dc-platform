package ae.gov.dubaicustoms.platform.tck.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.spi.RateLimiterProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link RateLimiterProvider} providers. Extend this
 * class, supply a provider from {@link #provider()}, and inherit the whole suite; a provider is
 * <em>platform-certified</em> for rate limiting iff this class passes against it.
 *
 * <p>Each {@code tryAcquire(key, limit, window)} consumes one permit against {@code limit} permits per
 * {@code window}. Invariants verified:
 * <ol>
 *   <li>calls up to the limit are allowed;</li>
 *   <li>the call past the limit is denied and carries a positive retry hint;</li>
 *   <li>a limit of one admits exactly one call per window;</li>
 *   <li>distinct keys have independent budgets;</li>
 *   <li>an allowed decision carries a zero retry hint;</li>
 *   <li>a denial's retry hint never exceeds the window;</li>
 *   <li>concurrent callers never exceed the limit (thread-safe accounting);</li>
 *   <li>the budget refreshes after the window elapses (relaxable via {@link #windowRefreshes()}).</li>
 * </ol>
 *
 * @since 0.2.0
 */
public abstract class RateLimiterProviderTck {

    private RateLimiterProvider subject;

    /**
     * Supplies the provider under test. The same instance is reused within a test.
     *
     * @return the {@link RateLimiterProvider}; never {@code null}
     */
    protected abstract RateLimiterProvider provider();

    /**
     * Whether the provider refreshes a key's budget once its window elapses. Override to return
     * {@code false} for a provider without time-based refresh.
     *
     * @return {@code true} if the budget refreshes after the window
     */
    protected boolean windowRefreshes() {
        return true;
    }

    @BeforeEach
    void createSubject() {
        subject = provider();
    }

    @Test
    void allowsCallsUpToTheLimit() {
        String key = unique();
        Duration window = Duration.ofSeconds(60);
        for (int i = 0; i < 5; i++) {
            assertThat(subject.tryAcquire(key, 5, window).allowed())
                    .as("call %s of 5 within the limit", i + 1)
                    .isTrue();
        }
    }

    @Test
    void deniesTheCallPastTheLimitWithARetryHint() {
        String key = unique();
        Duration window = Duration.ofSeconds(60);
        for (int i = 0; i < 3; i++) {
            subject.tryAcquire(key, 3, window);
        }

        Decision denied = subject.tryAcquire(key, 3, window);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void aLimitOfOneAdmitsExactlyOnePerWindow() {
        String key = unique();
        Duration window = Duration.ofSeconds(60);
        assertThat(subject.tryAcquire(key, 1, window).allowed()).isTrue();
        assertThat(subject.tryAcquire(key, 1, window).allowed()).isFalse();
    }

    @Test
    void aDenialRetryHintNeverExceedsTheWindow() {
        String key = unique();
        Duration window = Duration.ofSeconds(30);
        subject.tryAcquire(key, 1, window);
        Decision denied = subject.tryAcquire(key, 1, window);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isLessThanOrEqualTo(window);
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        String key = unique();
        int limit = 5;
        int callers = 40;
        Duration window = Duration.ofSeconds(60);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    awaitLatch(start);
                    if (subject.tryAcquire(key, limit, window).allowed()) {
                        allowed.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(allowed.get()).isBetween(1, limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distinctKeysHaveIndependentBudgets() {
        Duration window = Duration.ofSeconds(60);
        String keyA = unique();
        String keyB = unique();
        assertThat(subject.tryAcquire(keyA, 1, window).allowed()).isTrue();
        assertThat(subject.tryAcquire(keyA, 1, window).allowed()).isFalse();
        // keyB is untouched, so its first call is still allowed.
        assertThat(subject.tryAcquire(keyB, 1, window).allowed()).isTrue();
    }

    @Test
    void anAllowedDecisionCarriesNoWait() {
        Decision allowed = subject.tryAcquire(unique(), 1, Duration.ofSeconds(60));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.retryAfter()).isZero();
    }

    @Test
    void theBudgetRefreshesAfterTheWindow() {
        if (!windowRefreshes()) {
            return;
        }
        String key = unique();
        Duration window = Duration.ofMillis(300);
        assertThat(subject.tryAcquire(key, 1, window).allowed()).isTrue();
        assertThat(subject.tryAcquire(key, 1, window).allowed()).isFalse();

        // After the window rolls over, the key is allowed again.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(subject.tryAcquire(key, 1, window).allowed()).isTrue());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String unique() {
        return "tck-" + UUID.randomUUID();
    }
}
