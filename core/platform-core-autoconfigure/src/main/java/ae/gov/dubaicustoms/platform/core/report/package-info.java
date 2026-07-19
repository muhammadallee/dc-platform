/**
 * The capability report contract: every capability's auto-configuration contributes one
 * {@link ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor} bean, and the platform
 * banner logs the collected set at startup.
 *
 * <p>API for autoconfigure modules only — it lives here (not in core-api) so core-api stays
 * Spring-free.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.core.report;
