/**
 * Shared public contracts of the DC platform: API stability markers ({@link ae.gov.dubaicustoms.platform.core.PlatformApi},
 * {@link ae.gov.dubaicustoms.platform.core.PlatformInternal}), the platform exception model
 * ({@link ae.gov.dubaicustoms.platform.core.PlatformException} carrying a stable
 * {@link ae.gov.dubaicustoms.platform.core.ErrorCode}), and the correlation request context under
 * {@code ae.gov.dubaicustoms.platform.core.context}.
 *
 * <p>Consumed by every capability's api/spi/impl modules and by application code. Deliberately
 * Spring-free and capped at 25 public types (enforced by an executable test): only concepts needed
 * by three or more capabilities belong here.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.core;
