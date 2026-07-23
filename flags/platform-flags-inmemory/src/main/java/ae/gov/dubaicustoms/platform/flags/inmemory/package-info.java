/**
 * The in-memory feature-flag provider:
 * {@link ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider} serves flags from a
 * concurrent map seeded by configuration and mutable at runtime. The default provider, selected by
 * {@code platform-flags-autoconfigure} when no other provider is present.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.flags.inmemory;
