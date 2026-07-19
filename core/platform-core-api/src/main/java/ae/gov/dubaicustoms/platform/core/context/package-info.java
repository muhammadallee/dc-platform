/**
 * The correlation request context: {@link ae.gov.dubaicustoms.platform.core.context.CorrelationId}
 * as the propagated identifier and {@link ae.gov.dubaicustoms.platform.core.context.RequestContext}
 * as read-only static access to the current context.
 *
 * <p>Population is done exclusively by platform filters and interceptors (core-autoconfigure's
 * correlation filter in web apps, messaging listeners later); application code only reads.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.core.context;
