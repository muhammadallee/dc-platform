/**
 * Internal implementation details of the idempotency auto-configuration (the JDBC/Redis stores, the
 * {@code @Idempotent} pointcut/interceptor/advisor/registrar, and the HTTP filter). Not API; do not
 * depend on types here from outside this module.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;
