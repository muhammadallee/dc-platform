/**
 * Consumer conformance rules: {@link ae.gov.dubaicustoms.platform.test.arch.PlatformUsageRules}
 * exposes ArchUnit rules a platform service runs over its own production classes (via the archetype's
 * generated {@code PlatformConformanceTest}) to prove it uses platform APIs instead of hand-rolling
 * messaging infrastructure, exception handling, or environment access. Each rule's message names the
 * platform alternative, so a violation teaches the fix.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.test.arch;
