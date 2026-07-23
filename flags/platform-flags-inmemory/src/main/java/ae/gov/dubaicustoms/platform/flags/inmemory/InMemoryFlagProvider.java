package ae.gov.dubaicustoms.platform.flags.inmemory;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FlagProvider}: flags live in a concurrent map seeded from configuration
 * ({@code dc.platform.flags.static.*}) and mutable at runtime. The default, zero-infrastructure
 * provider — ideal for local demos and tests where flipping a flag should be immediate.
 *
 * <p>Evaluation ignores the {@link EvaluationContext}: values are global, not per-user. Runtime
 * mutation ({@link #set}/{@link #remove}) is what the {@code platformflags} actuator endpoint drives.
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap}.
 *
 * @since 0.2.0
 */
public final class InMemoryFlagProvider implements FlagProvider {

    private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();

    /**
     * Creates a provider seeded with {@code initial} flag values.
     *
     * @param initial the initial flag values (typically bound from configuration); never {@code null}
     */
    public InMemoryFlagProvider(Map<String, ?> initial) {
        Objects.requireNonNull(initial, "initial must not be null");
        initial.forEach((key, value) -> {
            if (value != null) {
                values.put(key, value);
            }
        });
    }

    @Override
    public Optional<FlagValue> evaluate(String flag, EvaluationContext context) {
        return Optional.ofNullable(values.get(flag)).map(FlagValue::new);
    }

    /**
     * Sets (or replaces) a flag's value at runtime.
     *
     * @param flag the flag key
     * @param value the value
     */
    public void set(String flag, Object value) {
        values.put(Objects.requireNonNull(flag, "flag must not be null"),
                Objects.requireNonNull(value, "value must not be null"));
    }

    /**
     * Removes a flag at runtime; subsequent evaluations return empty for it.
     *
     * @param flag the flag key
     * @return whether the flag was present
     */
    public boolean remove(String flag) {
        return values.remove(flag) != null;
    }

    /**
     * An immutable snapshot of the current flags.
     *
     * @return a copy of the flag map
     */
    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
