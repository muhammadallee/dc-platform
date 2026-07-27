package ae.gov.dubaicustoms.platform.audit.spi;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import org.apiguardian.api.API;

/**
 * The pluggable destination an {@link ae.gov.dubaicustoms.platform.audit.Auditor Auditor} writes to.
 * Each provider module supplies one implementation: a structured logger (the default), a JDBC table,
 * or the messaging transport.
 *
 * <p>Called from the platform's audit background worker, not the request thread, so {@code write} may
 * block on I/O. Implementations must be thread-safe (a single instance is shared) and should let
 * exceptions propagate — the worker logs and drops a failed event rather than losing the request.
 *
 * @since 0.2.0
 */
@FunctionalInterface
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public interface AuditSink {

    /**
     * Writes {@code event} to this sink.
     *
     * @param event the event to persist; never {@code null}
     */
    void write(AuditEvent event);
}
