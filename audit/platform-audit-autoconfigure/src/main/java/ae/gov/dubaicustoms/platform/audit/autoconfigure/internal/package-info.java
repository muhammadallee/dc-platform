/**
 * Module-private internals of the audit autoconfigure: the async {@link
 * ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.AsyncAuditor}, the {@code @Audited}
 * advisor pieces, the {@link
 * ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.ActorResolver} seam, and the guarded
 * {@link ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.MessagingAuditSink}.
 *
 * <p>Not API: types here may change without notice. Applications use {@code Auditor} and
 * {@code @Audited} from {@code platform-audit-api}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;
