/**
 * Technology Compatibility Kit for the audit capability: {@link
 * ae.gov.dubaicustoms.platform.tck.audit.AuditSinkTck} is the abstract contract every {@code AuditSink}
 * must satisfy to be platform-certified (an event persists, core fields survive the round trip, the
 * trail is append-only, null resource/correlationId are tolerated, and concurrent writes are safe).
 *
 * @since 1.0.0
 */
package ae.gov.dubaicustoms.platform.tck.audit;
