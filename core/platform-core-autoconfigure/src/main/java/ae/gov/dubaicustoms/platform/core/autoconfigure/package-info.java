/**
 * Auto-configuration for the core capability: {@code CoreContextAutoConfiguration} installs the
 * correlation-id filter in servlet web apps; {@code PlatformBannerAutoConfiguration} logs the
 * one-line capability banner at startup. Everything backs off behind
 * {@code dc.platform.core.enabled} and user-supplied beans.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.core.autoconfigure;
