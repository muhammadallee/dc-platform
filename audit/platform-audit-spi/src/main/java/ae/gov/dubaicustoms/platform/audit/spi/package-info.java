/**
 * The audit provider contract: {@link ae.gov.dubaicustoms.platform.audit.spi.AuditSink} is the
 * pluggable destination an {@code Auditor} writes {@code AuditEvent}s to.
 *
 * <p>Provider modules implement it (structured log, JDBC, messaging); the audit autoconfigure selects
 * the active sink by a graceful-degradation chain and wraps it in the async {@code Auditor}.
 * Applications never depend on this module directly.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.spi;
