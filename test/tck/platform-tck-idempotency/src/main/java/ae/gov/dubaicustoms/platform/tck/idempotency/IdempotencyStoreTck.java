package ae.gov.dubaicustoms.platform.tck.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link IdempotencyStore} implementations. Extend
 * this class, supply a fresh store via {@link #store()} and a clock hook via
 * {@link #advanceTimePast(Duration)}, and inherit the whole suite; a store is
 * <em>platform-certified</em> for idempotency iff this class passes against it.
 *
 * <p>Invariants verified (mirroring {@link IdempotencyStore#putIfAbsent}'s documented requirements):
 * <ol>
 *   <li>the first occurrence of a key is recorded ({@code true});</li>
 *   <li>a duplicate while the key is still live is rejected ({@code false});</li>
 *   <li>distinct keys are independent;</li>
 *   <li>a key is accepted again once its TTL has elapsed (self-expiry);</li>
 *   <li>recording is atomic — under concurrent callers racing the same key, exactly one wins.</li>
 * </ol>
 *
 * @since 1.0.0
 */
public abstract class IdempotencyStoreTck {

    /**
     * Supplies a fresh, empty store bound to a clock that {@link #advanceTimePast(Duration)} controls.
     *
     * @return a new {@link IdempotencyStore}; never {@code null}
     */
    protected abstract IdempotencyStore store();

    /**
     * Advances the clock backing the store returned by {@link #store()} by strictly more than
     * {@code ttl}, so a key recorded with that TTL is guaranteed to have expired. Implemented by the
     * provider so expiry is exercised deterministically without sleeping.
     *
     * @param ttl the TTL to advance past; never {@code null}
     */
    protected abstract void advanceTimePast(Duration ttl);

    @Test
    void firstOccurrenceOfAKeyIsRecorded() {
        assertThat(store().putIfAbsent("order-1", Duration.ofHours(24))).isTrue();
    }

    @Test
    void aDuplicateWhileLiveIsRejected() {
        IdempotencyStore store = store();
        assertThat(store.putIfAbsent("order-1", Duration.ofHours(24))).isTrue();
        assertThat(store.putIfAbsent("order-1", Duration.ofHours(24))).isFalse();
    }

    @Test
    void distinctKeysAreIndependent() {
        IdempotencyStore store = store();
        assertThat(store.putIfAbsent("order-1", Duration.ofHours(24))).isTrue();
        assertThat(store.putIfAbsent("order-2", Duration.ofHours(24))).isTrue();
    }

    @Test
    void aKeyIsAcceptedAgainAfterItsTtlExpires() {
        IdempotencyStore store = store();
        Duration ttl = Duration.ofSeconds(30);
        assertThat(store.putIfAbsent("order-1", ttl)).isTrue();
        assertThat(store.putIfAbsent("order-1", ttl)).isFalse();

        advanceTimePast(ttl);

        assertThat(store.putIfAbsent("order-1", ttl)).isTrue();
    }

    @Test
    void recordingIsAtomicUnderConcurrentCallers() throws InterruptedException {
        IdempotencyStore store = store();
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(); // release all callers at once to maximise contention on the key
                    return store.putIfAbsent("race", Duration.ofMinutes(5));
                }));
            }
            ready.await();
            go.countDown();

            long winners = 0;
            for (Future<Boolean> result : results) {
                try {
                    if (result.get()) {
                        winners++;
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("a concurrent putIfAbsent threw", e.getCause());
                }
            }
            assertThat(winners)
                    .as("exactly one concurrent caller may record a live key")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
