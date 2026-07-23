package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Auditor;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform {@link Auditor}: hands each event to a bounded in-memory queue drained by a single
 * daemon worker that writes to the selected {@link AuditSink}. {@code record} never blocks the caller
 * and never throws — a full queue drops the event with a WARN.
 *
 * <p><strong>Tradeoff (decision D52):</strong> auditing is fire-and-forget. Under sustained overload
 * we shed audit records rather than add latency to — or back-pressure — the request path. Sinks that
 * must not lose records should raise {@code dc.platform.audit.queue-capacity} and provision the
 * backing store accordingly, or replace this bean with a synchronous {@code Auditor}.
 *
 * <p>Thread-safe; a single instance is shared platform-wide. Implements {@link AutoCloseable} so
 * Spring drains and stops the worker on context shutdown.
 *
 * @since 0.2.0
 */
public final class AsyncAuditor implements Auditor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditor.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final AuditSink sink;
    private final BlockingQueue<AuditEvent> queue;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    /**
     * @param sink the selected audit sink to write events to
     * @param queueCapacity bound on the in-memory queue; full-queue offers are dropped with a WARN
     */
    public AsyncAuditor(AuditSink sink, int queueCapacity) {
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.worker = new Thread(this::drain, "platform-audit-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // Non-blocking offer: shed rather than block the request thread when the queue is saturated.
        if (!queue.offer(event)) {
            long total = dropped.incrementAndGet();
            log.warn("[DC-AUDIT-0500] audit queue full; dropped event action={} (total dropped={})",
                    event.action(), total);
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                AuditEvent event = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeQuietly(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeQuietly(AuditEvent event) {
        try {
            sink.write(event);
        } catch (RuntimeException e) {
            // A failing sink must not kill the worker; log and drop this one event.
            log.warn("[DC-AUDIT-0501] audit sink {} failed; dropped event action={}",
                    sink.getClass().getSimpleName(), event.action(), e);
        }
    }

    /** Number of events dropped because the queue was full (for tests and diagnostics). */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        // Stop accepting new work and let the worker drain what remains before it exits (the poll
        // wakes every POLL_INTERVAL to re-check `running`), so shutdown does not lose queued events.
        running = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
