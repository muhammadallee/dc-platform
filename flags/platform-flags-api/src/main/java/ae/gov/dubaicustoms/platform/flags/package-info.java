/**
 * The feature-flag capability contract: {@link ae.gov.dubaicustoms.platform.flags.FeatureFlags}
 * evaluates boolean and typed flags, and {@link ae.gov.dubaicustoms.platform.flags.FeatureGate} gates
 * a method behind a flag.
 *
 * <p>Applications depend only on this module; {@code platform-flags-spi} defines the provider contract,
 * and {@code platform-flags-autoconfigure} supplies the implementation over whichever
 * {@code FlagProvider} is on the classpath (in-memory by default, OpenFeature when present).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.flags;
