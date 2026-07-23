/**
 * The feature-flag provider contract: {@link ae.gov.dubaicustoms.platform.flags.spi.FlagProvider}
 * resolves a flag against an {@link ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext},
 * returning an {@link ae.gov.dubaicustoms.platform.flags.spi.FlagValue}. Implemented by the inmemory
 * and openfeature provider modules; applications never depend on this module directly.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.flags.spi;
