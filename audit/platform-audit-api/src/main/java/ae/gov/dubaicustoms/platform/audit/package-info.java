/**
 * The audit capability contract: {@link ae.gov.dubaicustoms.platform.audit.Auditor} records
 * {@link ae.gov.dubaicustoms.platform.audit.AuditEvent}s programmatically, and
 * {@link ae.gov.dubaicustoms.platform.audit.Audited} audits a method declaratively.
 *
 * <p>Applications depend only on this module; {@code platform-audit-spi} defines the pluggable sink
 * contract, and {@code platform-audit-autoconfigure} supplies the {@code Auditor} implementation over
 * whichever {@code AuditSink} is active (structured log by default, JDBC or messaging when present).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit;
