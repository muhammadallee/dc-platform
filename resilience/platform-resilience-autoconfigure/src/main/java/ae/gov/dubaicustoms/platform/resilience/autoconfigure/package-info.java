/**
 * Auto-configuration for the resilience capability: contributes the platform's default Resilience4j
 * instance tuning as lowest-precedence {@code resilience4j.*} environment defaults and the
 * {@link ae.gov.dubaicustoms.platform.resilience.RetryableOperation} bean over the retry registry.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.resilience.autoconfigure;
