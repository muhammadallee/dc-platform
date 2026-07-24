package ae.gov.dubaicustoms.platform.tck.locking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link LockProvider} providers. Extend this class,
 * supply a provider from {@link #provider()}, and inherit the whole suite; a provider is
 * <em>platform-certified</em> for locking iff this class passes against it.
 *
 * <p>Invariants verified:
 * <ol>
 *   <li>a free lock is acquired;</li>
 *   <li>a second acquire of a held lock returns {@link Optional#empty()} (non-reentrant);</li>
 *   <li>releasing a lock lets it be re-acquired;</li>
 *   <li>releasing a handle is idempotent;</li>
 *   <li>a handle releases only the lock it took — never one a later holder re-acquired (fencing);</li>
 *   <li>distinct lock names are independent;</li>
 *   <li>concurrent contention is safe — at least one caller acquires and no call throws — and,
 *       for backends that serialize acquisition, exactly one caller wins (relaxable via
 *       {@link #enforcesExclusionUnderConcurrency()});</li>
 *   <li>a lock auto-expires after its lease (relaxable via {@link #supportsExpiry()}).</li>
 * </ol>
 *
 * @since 0.2.0
 */
public abstract class LockProviderTck {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private LockProvider subject;

    /**
     * Supplies the provider under test. The same instance is reused within a test.
     *
     * @return the {@link LockProvider}; never {@code null}
     */
    protected abstract LockProvider provider();

    /**
     * Whether the provider auto-expires a lock after its lease. Override to return {@code false} for a
     * provider without time-based expiry.
     *
     * @return {@code true} if leases expire on their own
     */
    protected boolean supportsExpiry() {
        return true;
    }

    /**
     * Whether the backend serializes concurrent acquisition strongly enough that exactly one of many
     * simultaneous callers wins. Override to return {@code false} for a backend that does not (e.g. an
     * embedded in-memory database whose engine does not reliably serialize a reclaim-then-insert across
     * connections); the concurrent stress still runs and must still yield at least one winner and no
     * error. Real databases and Redis pass the strict form.
     *
     * @return {@code true} to assert exactly one winner under contention
     */
    protected boolean enforcesExclusionUnderConcurrency() {
        return true;
    }

    // Lazily created on first use so a subclass's own @BeforeEach (e.g. schema setup) runs first —
    // superclass @BeforeEach callbacks would otherwise call provider() before that setup.
    private LockProvider subject() {
        if (subject == null) {
            subject = provider();
        }
        return subject;
    }

    @Test
    void acquiresAFreeLock() {
        Optional<LockHandle> handle = subject().tryAcquire(unique("free"), LEASE);
        assertThat(handle).isPresent();
        handle.get().close();
    }

    @Test
    void secondAcquireOfAHeldLockIsEmpty() {
        String name = unique("held");
        LockHandle held = subject().tryAcquire(name, LEASE).orElseThrow();
        try {
            assertThat(subject().tryAcquire(name, LEASE)).isEmpty();
        } finally {
            held.close();
        }
    }

    @Test
    void releasingLetsItBeReacquired() {
        String name = unique("recycle");
        subject().tryAcquire(name, LEASE).orElseThrow().close();
        Optional<LockHandle> again = subject().tryAcquire(name, LEASE);
        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    void releaseIsIdempotent() {
        LockHandle handle = subject().tryAcquire(unique("idem"), LEASE).orElseThrow();
        handle.close();
        handle.close();
    }

    @Test
    void aHandleReleasesOnlyItsOwnLock() {
        String name = unique("fence");
        LockHandle first = subject().tryAcquire(name, LEASE).orElseThrow();
        first.close();
        LockHandle second = subject().tryAcquire(name, LEASE).orElseThrow();

        // Closing the first handle again must NOT release the lock the second holder now owns.
        first.close();
        assertThat(subject().tryAcquire(name, LEASE)).isEmpty();
        second.close();
    }

    @Test
    void distinctNamesAreIndependent() {
        LockHandle a = subject().tryAcquire(unique("a"), LEASE).orElseThrow();
        LockHandle b = subject().tryAcquire(unique("b"), LEASE).orElseThrow();
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        a.close();
        b.close();
    }

    @Test
    void onlyOneCallerWinsUnderContention() throws Exception {
        String name = unique("contended");
        subject(); // prime lazy init before concurrent use
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<LockHandle>>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Optional<LockHandle>> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return subject().tryAcquire(name, LEASE);
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            long winners = 0;
            for (Future<Optional<LockHandle>> result : results) {
                // get() also surfaces any exception thrown inside a worker: the provider must be safe.
                Optional<LockHandle> handle = result.get(10, TimeUnit.SECONDS);
                if (handle.isPresent()) {
                    winners++;
                    handle.get().close();
                }
            }
            assertThat(winners).as("at least one caller must acquire under contention").isGreaterThanOrEqualTo(1);
            if (enforcesExclusionUnderConcurrency()) {
                assertThat(winners).as("exactly one caller may hold the lock under contention").isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLockAutoExpiresAfterItsLease() {
        if (!supportsExpiry()) {
            return;
        }
        String name = unique("expiring");
        LockHandle handle = subject().tryAcquire(name, Duration.ofMillis(200)).orElseThrow();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<LockHandle> reacquired = subject().tryAcquire(name, Duration.ofMillis(200));
            assertThat(reacquired).isPresent();
            reacquired.get().close();
        });
        handle.close();
    }

    private static String unique(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }
}
