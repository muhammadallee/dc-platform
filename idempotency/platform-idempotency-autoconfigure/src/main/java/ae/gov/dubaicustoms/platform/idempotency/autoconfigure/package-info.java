/**
 * Auto-configuration for the idempotency capability: an
 * {@link ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore} selected by classpath (Redis over
 * JDBC), the SpEL-driven {@link ae.gov.dubaicustoms.platform.idempotency.Idempotent @Idempotent}
 * advisor, and the optional {@code Idempotency-Key} HTTP filter.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.idempotency.autoconfigure;
