package ae.gov.dubaicustoms.platform.flags.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.flags.FeatureFlags;
import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import java.util.List;
import java.util.Optional;

/**
 * The platform {@link FeatureFlags}: resolves a flag against the ordered {@link FlagProvider} beans
 * (first provider that knows the flag wins), evaluating against the context from the
 * {@link EvaluationContextProvider}. Exists so applications get a stable facade independent of which
 * provider (in-memory, OpenFeature) backs it.
 */
public final class DefaultFeatureFlags implements FeatureFlags {

    private final List<FlagProvider> providers;
    private final EvaluationContextProvider contextProvider;

    public DefaultFeatureFlags(List<FlagProvider> providers, EvaluationContextProvider contextProvider) {
        this.providers = List.copyOf(providers);
        this.contextProvider = contextProvider;
    }

    @Override
    public boolean enabled(String flag) {
        return resolve(flag).map(FlagValue::asBoolean).orElse(false);
    }

    @Override
    public <T> T value(String flag, T defaultValue) {
        return resolve(flag).flatMap(value -> coerce(value, defaultValue)).orElse(defaultValue);
    }

    private Optional<FlagValue> resolve(String flag) {
        EvaluationContext context = contextProvider.get();
        for (FlagProvider provider : providers) {
            Optional<FlagValue> resolved = provider.evaluate(flag, context);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> coerce(FlagValue value, T defaultValue) {
        Object raw = value.value();
        Class<?> target = defaultValue.getClass();
        if (target.isInstance(raw)) {
            return Optional.of((T) raw);
        }
        String text = String.valueOf(raw);
        try {
            if (target == Boolean.class) {
                return Optional.of((T) Boolean.valueOf(text));
            }
            if (target == String.class) {
                return Optional.of((T) text);
            }
            if (target == Integer.class) {
                return Optional.of((T) Integer.valueOf(text));
            }
            if (target == Long.class) {
                return Optional.of((T) Long.valueOf(text));
            }
            if (target == Double.class) {
                return Optional.of((T) Double.valueOf(text));
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
