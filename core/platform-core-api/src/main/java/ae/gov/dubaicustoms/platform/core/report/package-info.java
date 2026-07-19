/**
 * The capability report contract: every capability's auto-configuration contributes one
 * {@link ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor} bean, and the platform
 * banner logs the collected set at startup.
 *
 * <p>API for autoconfigure modules only. It lives in core-api (moved from core-autoconfigure in
 * phase 4) because every capability's auto-configuration must reference it, and the dependency
 * constitution lets autoconfigure modules reach other capabilities only through their -api
 * artifacts. The types are Spring-free, so core-api stays Spring-free too.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.core.report;
