package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.FieldMasker;

/**
 * Replaces the whole value of a credential-named field ({@code password}, {@code token},
 * {@code apiKey}, …), at any depth, before it is written: strings, numbers, booleans, binary values,
 * objects and arrays alike. The value masker alone cannot do this, because the JSON generator never
 * passes booleans or binary values to value maskers.
 *
 * <p>Stateless; thread-safe.
 */
public final class CredentialFieldMasker implements FieldMasker {

    @Override
    public Object mask(JsonStreamContext context) {
        return PlatformValueMasker.isCredentialKey(context.getCurrentName()) ? PlatformValueMasker.MASK : null;
    }
}
