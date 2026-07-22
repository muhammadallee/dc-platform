/**
 * The idempotency capability contract:
 * {@link ae.gov.dubaicustoms.platform.idempotency.Idempotent @Idempotent} marks a method whose effect
 * must happen at most once per key within a TTL, and
 * {@link ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore} records seen keys.
 *
 * <p>{@code platform-idempotency-autoconfigure} supplies the store (JDBC by default, Redis when
 * present), the SpEL-driven advisor, and the optional {@code Idempotency-Key} HTTP filter.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.idempotency;
