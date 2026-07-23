package ae.gov.dubaicustoms.platform.flags.openfeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.flags.spi.EvaluationContext;
import ae.gov.dubaicustoms.platform.flags.spi.FlagValue;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Value;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Adapter behaviour against a mocked OpenFeature Client. */
class OpenFeatureFlagProviderTest {

    private final Client client = mock(Client.class);
    private final OpenFeatureFlagProvider provider = new OpenFeatureFlagProvider(client);

    private static FlagEvaluationDetails<Value> details(Value value, ErrorCode errorCode) {
        return FlagEvaluationDetails.<Value>builder().flagKey("f").value(value).errorCode(errorCode).build();
    }

    @Test
    void resolvesAKnownFlagToItsValue() {
        when(client.getObjectDetails(eq("beta"), any(Value.class), any()))
                .thenReturn(details(new Value(true), null));

        Optional<FlagValue> result = provider.evaluate("beta", EvaluationContext.anonymous());
        assertThat(result).map(FlagValue::asBoolean).contains(true);
    }

    @Test
    void mapsFlagNotFoundToEmpty() {
        when(client.getObjectDetails(eq("missing"), any(Value.class), any()))
                .thenReturn(details(new Value(), ErrorCode.FLAG_NOT_FOUND));

        assertThat(provider.evaluate("missing", EvaluationContext.anonymous())).isEmpty();
    }

    @Test
    void mapsNullValueToEmpty() {
        when(client.getObjectDetails(eq("null-flag"), any(Value.class), any()))
                .thenReturn(details(new Value(), null));

        assertThat(provider.evaluate("null-flag", EvaluationContext.anonymous())).isEmpty();
    }

    @Test
    void passesUserAsTargetingKeyAndTenantAndAttributes() {
        when(client.getObjectDetails(eq("f"), any(Value.class), any()))
                .thenReturn(details(new Value("on"), null));

        provider.evaluate("f", new EvaluationContext(
                Optional.of("user-1"), Optional.of("tenant-9"), Map.of("region", "AE")));

        ArgumentCaptor<dev.openfeature.sdk.EvaluationContext> captor =
                ArgumentCaptor.forClass(dev.openfeature.sdk.EvaluationContext.class);
        verify(client).getObjectDetails(eq("f"), any(Value.class), captor.capture());
        dev.openfeature.sdk.EvaluationContext sent = captor.getValue();
        assertThat(sent.getTargetingKey()).isEqualTo("user-1");
        assertThat(sent.getValue("tenant").asString()).isEqualTo("tenant-9");
        assertThat(sent.getValue("region").asString()).isEqualTo("AE");
    }
}
