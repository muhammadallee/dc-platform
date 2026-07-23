/**
 * Auto-configuration for the audit capability: selects the active {@code AuditSink} by a messaging
 * &rarr; jdbc &rarr; log degradation chain, wraps it in the async {@code Auditor}, and enforces
 * {@code @Audited} through a plain AOP advisor.
 *
 * <p>The sink configs
 * ({@link ae.gov.dubaicustoms.platform.audit.autoconfigure.MessagingAuditSinkAutoConfiguration},
 * {@link ae.gov.dubaicustoms.platform.audit.autoconfigure.JdbcAuditSinkAutoConfiguration},
 * {@link ae.gov.dubaicustoms.platform.audit.autoconfigure.LogAuditSinkAutoConfiguration}) contribute
 * exactly one sink by priority; the security bridge
 * ({@link ae.gov.dubaicustoms.platform.audit.autoconfigure.AuditSecurityAutoConfiguration}) resolves
 * the actor when the security capability is present; and
 * {@link ae.gov.dubaicustoms.platform.audit.autoconfigure.PlatformAuditAutoConfiguration} wires the
 * {@code Auditor}, descriptor, and advisor. Every bean backs off on a user-supplied equivalent.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.autoconfigure;
