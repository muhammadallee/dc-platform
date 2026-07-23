/**
 * Auto-configuration for the feature-flags capability: selects the {@code FlagProvider} by classpath
 * (OpenFeature when an OpenFeature {@code Client} bean is present, otherwise the in-memory provider),
 * exposes {@link ae.gov.dubaicustoms.platform.flags.FeatureFlags}, enforces
 * {@link ae.gov.dubaicustoms.platform.flags.FeatureGate} via a plain AOP advisor, resolves the current
 * user/tenant into the evaluation context when the security capability is present, and publishes the
 * {@code platformflags} actuator endpoint.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.flags.autoconfigure;
