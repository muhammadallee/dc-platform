package ae.gov.dubaicustoms.platform.flags.openfeature;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts the platform {@link FlagProvider} contract to the OpenFeature SDK, so any OpenFeature
 * provider registered downstream (LaunchDarkly, Flagsmith, a file-based provider, …) backs the
 * capability. Exists to give enterprise flag vendors a plug-in seam without leaking their SDKs into
 * platform code.
 *
 * <p>Flags are read type-agnostically via {@code getObjectDetails}; a {@code FLAG_NOT_FOUND} result (or
 * a null value) maps to {@link Optional#empty()} so the platform falls back to the caller's default.
 * The platform {@link EvaluationContext} maps to OpenFeature's: user id → targeting key, tenant id and
 * attributes → context fields.
 *
 * <p>Thread-safe: the OpenFeature {@link Client} is thread-safe and this adapter holds no mutable state.
 *
 * @since 0.2.0
 */
public final class OpenFeatureFlagProvider implements FlagProvider {

    private final Client client;

    /**
     * Creates the adapter over an OpenFeature client.
     *
     * @param client the OpenFeature client; never {@code null}
     */
    public OpenFeatureFlagProvider(Client client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public Optional<FlagValue> evaluate(String flag, EvaluationContext context) {
        FlagEvaluationDetails<Value> details = client.getObjectDetails(flag, new Value(), toOpenFeature(context));
        if (details.getErrorCode() == ErrorCode.FLAG_NOT_FOUND) {
            return Optional.empty();
        }
        Value value = details.getValue();
        if (value == null || value.asObject() == null) {
            return Optional.empty();
        }
        return Optional.of(new FlagValue(value.asObject()));
    }

    private static MutableContext toOpenFeature(EvaluationContext context) {
        MutableContext openFeature = new MutableContext();
        context.userId().ifPresent(openFeature::setTargetingKey);
        context.tenantId().ifPresent(tenant -> openFeature.add("tenant", tenant));
        context.attributes().forEach((key, attributeValue) -> openFeature.add(key, String.valueOf(attributeValue)));
        return openFeature;
    }
}
