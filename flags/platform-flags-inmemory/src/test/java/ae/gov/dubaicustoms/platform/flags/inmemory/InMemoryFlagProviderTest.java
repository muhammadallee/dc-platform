package ae.gov.dubaicustoms.platform.flags.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Evaluation and runtime mutation of the in-memory provider. */
class InMemoryFlagProviderTest {

    private final InMemoryFlagProvider provider =
            new InMemoryFlagProvider(Map.of("beta", "true", "batch-size", "100"));

    @Test
    void evaluatesKnownFlagsAndEmptyForUnknown() {
        assertThat(provider.evaluate("beta", EvaluationContext.anonymous()))
                .map(FlagValue::asBoolean).contains(true);
        assertThat(provider.evaluate("batch-size", EvaluationContext.anonymous()))
                .map(FlagValue::value).contains("100");
        assertThat(provider.evaluate("unknown", EvaluationContext.anonymous())).isEmpty();
    }

    @Test
    void setAndRemoveMutateAtRuntime() {
        assertThat(provider.evaluate("gamma", EvaluationContext.anonymous())).isEmpty();

        provider.set("gamma", Boolean.TRUE);
        assertThat(provider.evaluate("gamma", EvaluationContext.anonymous()))
                .map(FlagValue::asBoolean).contains(true);

        assertThat(provider.remove("gamma")).isTrue();
        assertThat(provider.remove("gamma")).isFalse();
        assertThat(provider.evaluate("gamma", EvaluationContext.anonymous())).isEmpty();
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        Map<String, Object> snapshot = provider.snapshot();
        assertThat(snapshot).containsKeys("beta", "batch-size");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSeedValuesAreSkipped() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("present", "true");
        withNull.put("absent", null);
        InMemoryFlagProvider p = new InMemoryFlagProvider(withNull);
        assertThat(p.evaluate("present", EvaluationContext.anonymous())).isPresent();
        assertThat(p.evaluate("absent", EvaluationContext.anonymous())).isEmpty();
    }
}
