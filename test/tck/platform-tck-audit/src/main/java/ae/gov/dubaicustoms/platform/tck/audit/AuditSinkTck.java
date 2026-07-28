package ae.gov.dubaicustoms.platform.tck.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link AuditSink} providers. Extend this class,
 * supply the sink under test via {@link #sink()} and a read-back of what it persisted via
 * {@link #persistedCount()} / {@link #lastPersisted()}, and inherit the whole suite; a sink is
 * <em>platform-certified</em> for audit iff this class passes against it.
 *
 * <p>Invariants verified (from {@link AuditSink}'s contract):
 * <ol>
 *   <li>writing an event persists exactly one record;</li>
 *   <li>an event's core fields survive the round trip;</li>
 *   <li>the trail is append-only — writes accumulate, never overwrite;</li>
 *   <li>a null resource and correlation id are tolerated (both are nullable on {@link AuditEvent});</li>
 *   <li>the sink is thread-safe — concurrent writes all land (the sink is a shared singleton).</li>
 * </ol>
 *
 * <p>Sinks whose destination cannot be read back in-process (e.g. a pure log appender) certify the
 * subset of this contract they can observe by overriding the read-back hooks accordingly.
 *
 * @since 1.0.0
 */
public abstract class AuditSinkTck {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * The sink under test. The same instance is returned for the lifetime of one test (the read-back
     * hooks observe what was written to it).
     *
     * @return the {@link AuditSink} being certified; never {@code null}
     */
    protected abstract AuditSink sink();

    /**
     * The number of events {@link #sink()} has persisted so far.
     *
     * @return the persisted record count
     */
    protected abstract long persistedCount();

    /**
     * The core fields of the most recently persisted event, read back from the sink's destination.
     *
     * @return the last persisted event's core fields; never {@code null} when {@link #persistedCount()} &gt; 0
     */
    protected abstract PersistedAudit lastPersisted();

    /** The subset of an audit record that every sink must preserve verbatim (details serialisation varies). */
    protected record PersistedAudit(
            String action, String actor, String resource, Outcome outcome, String correlationId) {}

    @Test
    void writingAnEventPersistsExactlyOneRecord() {
        sink().write(event("order.create", "u1", "order:42", Outcome.SUCCESS, "cid-1", Map.of("total", 199)));
        assertThat(persistedCount()).isEqualTo(1);
    }

    @Test
    void persistsAnEventsCoreFields() {
        sink().write(event("order.create", "u1", "order:42", Outcome.SUCCESS, "cid-1", Map.of("total", 199)));
        assertThat(lastPersisted())
                .isEqualTo(new PersistedAudit("order.create", "u1", "order:42", Outcome.SUCCESS, "cid-1"));
    }

    @Test
    void theTrailIsAppendOnly() {
        AuditSink sink = sink();
        sink.write(event("a", "u", "r", Outcome.SUCCESS, "c", Map.of()));
        sink.write(event("b", "u", "r", Outcome.FAILURE, "c", Map.of()));
        assertThat(persistedCount()).isEqualTo(2);
    }

    @Test
    void toleratesANullResourceAndCorrelationId() {
        sink().write(event("order.list", "system", null, Outcome.FAILURE, null, Map.of()));
        assertThat(persistedCount()).isEqualTo(1);
        assertThat(lastPersisted())
                .isEqualTo(new PersistedAudit("order.list", "system", null, Outcome.FAILURE, null));
    }

    @Test
    void isThreadSafeAcrossConcurrentWrites() throws InterruptedException {
        AuditSink sink = sink();
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch ready = new CountDownLatch(writers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(); // release all writers at once
                    sink.write(event("evt-" + n, "u", "r", Outcome.SUCCESS, "c", Map.of()));
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> result : results) {
                try {
                    result.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("a concurrent write threw", e.getCause());
                }
            }
            assertThat(persistedCount()).as("every concurrent write must land").isEqualTo(writers);
        } finally {
            pool.shutdownNow();
        }
    }

    private static AuditEvent event(
            String action, String actor, String resource, Outcome outcome, String correlationId,
            Map<String, Object> details) {
        return new AuditEvent(action, actor, resource, outcome, AT, correlationId, details);
    }
}
