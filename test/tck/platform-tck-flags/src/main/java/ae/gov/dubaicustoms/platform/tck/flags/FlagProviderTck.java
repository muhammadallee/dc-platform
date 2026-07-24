package ae.gov.dubaicustoms.platform.tck.flags;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract test (Technology Compatibility Kit) for {@link FlagProvider} providers. Extend this class,
 * supply a provider seeded with given values via {@link #providerWith(Map)}, and inherit the whole
 * suite; a provider is <em>platform-certified</em> for feature flags iff this class passes against it.
 *
 * <p>Invariants verified:
 * <ol>
 *   <li>a seeded flag resolves to its value;</li>
 *   <li>an unknown flag resolves to {@link Optional#empty()} (total, never throwing);</li>
 *   <li>a boolean flag's {@link FlagValue#asBoolean()} reflects its value;</li>
 *   <li>string and numeric values are carried intact;</li>
 *   <li>{@code asBoolean()} is total for non-boolean values;</li>
 *   <li>evaluation tolerates an anonymous context (no user, no tenant);</li>
 *   <li>repeated evaluation of the same flag is stable;</li>
 *   <li>multiple seeded flags resolve independently.</li>
 * </ol>
 *
 * @since 0.2.0
 */
public abstract class FlagProviderTck {

    /**
     * Supplies a provider seeded with {@code seed} (flag key to value).
     *
     * @param seed the initial flag values; never {@code null}
     * @return a {@link FlagProvider} that knows exactly the seeded flags; never {@code null}
     */
    protected abstract FlagProvider providerWith(Map<String, Object> seed);

    @Test
    void resolvesASeededFlag() {
        FlagProvider provider = providerWith(Map.of("checkout-v2", true));
        assertThat(provider.evaluate("checkout-v2", EvaluationContext.anonymous())).isPresent();
    }

    @Test
    void unknownFlagIsEmpty() {
        FlagProvider provider = providerWith(Map.of("known", true));
        assertThat(provider.evaluate("does-not-exist", EvaluationContext.anonymous())).isEmpty();
    }

    @Test
    void booleanFlagReadsAsBoolean() {
        FlagProvider provider = providerWith(Map.of("on", true, "off", false));
        assertThat(provider.evaluate("on", EvaluationContext.anonymous()).orElseThrow().asBoolean()).isTrue();
        assertThat(provider.evaluate("off", EvaluationContext.anonymous()).orElseThrow().asBoolean()).isFalse();
    }

    @Test
    void carriesStringAndNumericValuesIntact() {
        FlagProvider provider = providerWith(Map.of("theme", "dark", "max-retries", 5));
        assertThat(provider.evaluate("theme", EvaluationContext.anonymous()).orElseThrow().value()).isEqualTo("dark");
        assertThat(provider.evaluate("max-retries", EvaluationContext.anonymous()).orElseThrow().value()).isEqualTo(5);
    }

    @Test
    void asBooleanIsTotalForNonBooleanValues() {
        FlagProvider provider = providerWith(Map.of("truthy", "true", "other", "nope"));
        assertThat(provider.evaluate("truthy", EvaluationContext.anonymous()).orElseThrow().asBoolean()).isTrue();
        assertThat(provider.evaluate("other", EvaluationContext.anonymous()).orElseThrow().asBoolean()).isFalse();
    }

    @Test
    void toleratesAnAnonymousContext() {
        FlagProvider provider = providerWith(Map.of("flag", true));
        // Must not throw for a context with no user and no tenant.
        assertThat(provider.evaluate("flag", EvaluationContext.anonymous())).isNotNull();
    }

    @Test
    void repeatedEvaluationIsStable() {
        FlagProvider provider = providerWith(Map.of("flag", true));
        Optional<FlagValue> first = provider.evaluate("flag", EvaluationContext.anonymous());
        Optional<FlagValue> second = provider.evaluate("flag", EvaluationContext.anonymous());
        assertThat(first).isPresent();
        assertThat(second.orElseThrow().value()).isEqualTo(first.orElseThrow().value());
    }

    @Test
    void multipleSeededFlagsResolveIndependently() {
        FlagProvider provider = providerWith(Map.of("alpha", true, "beta", "text", "gamma", 42));
        EvaluationContext context = EvaluationContext.anonymous();
        assertThat(provider.evaluate("alpha", context).orElseThrow().asBoolean()).isTrue();
        assertThat(provider.evaluate("beta", context).orElseThrow().value()).isEqualTo("text");
        assertThat(provider.evaluate("gamma", context).orElseThrow().value()).isEqualTo(42);
        assertThat(provider.evaluate("delta", context)).isEmpty();
    }
}
