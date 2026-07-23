package ae.gov.dubaicustoms.platform.audit;

/**
 * Records audit events programmatically, for the cases the {@link Audited} annotation cannot express
 * (conditional auditing, events raised deep inside a transaction, batch outcomes).
 *
 * <pre>{@code
 * @Service
 * class OrderService {
 *     private final Auditor auditor;
 *
 *     void cancel(Order order, String reason) {
 *         // ... perform the cancellation ...
 *         auditor.record(new AuditEvent(
 *                 "order.cancel", currentUser(), "order:" + order.id(), Outcome.SUCCESS,
 *                 Instant.now(), null, Map.of("reason", reason)));
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations are thread-safe and non-blocking to the caller: the platform default hands the
 * event to a bounded background queue and returns immediately (see the audit autoconfigure).
 *
 * @since 0.2.0
 */
public interface Auditor {

    /**
     * Records {@code event} to the configured audit sink.
     *
     * @param event the event to record; never {@code null}
     */
    void record(AuditEvent event);
}
