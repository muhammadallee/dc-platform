/**
 * The default audit sink: {@link ae.gov.dubaicustoms.platform.audit.log.LogAuditSink} writes each
 * {@code AuditEvent} as one structured line to the dedicated {@code AUDIT} logger.
 *
 * <p>Selected automatically by the audit autoconfigure as the always-available fallback; requires no
 * infrastructure and rides the platform's JSON logging pipeline.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.log;
