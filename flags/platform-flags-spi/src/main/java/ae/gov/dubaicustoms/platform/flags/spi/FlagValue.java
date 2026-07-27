package ae.gov.dubaicustoms.platform.flags.spi;

import java.util.Objects;
import org.apiguardian.api.API;

/**
 * A resolved flag value from a provider: an opaque carrier of the underlying value (a {@link Boolean},
 * {@link String}, {@link Number}, …) with a boolean view for the common on/off case.
 *
 * <p>Coercion to an arbitrary target type is the caller's concern (the platform {@code FeatureFlags}
 * implementation does it against the caller's default); this type only guarantees a total
 * {@link #asBoolean()}.
 *
 * <p>Value object; immutable and thread-safe. The wrapped value is never {@code null}.
 *
 * @param value the underlying resolved value; never {@code null}
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public record FlagValue(Object value) {

    /**
     * Validates the component.
     *
     * @throws NullPointerException if {@code value} is null
     */
    public FlagValue {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * The value as a boolean: the {@link Boolean} itself when it is one, otherwise
     * {@link Boolean#parseBoolean(String)} of its string form (so {@code "true"} → {@code true},
     * anything else → {@code false}).
     *
     * @return the boolean view of this value
     */
    public boolean asBoolean() {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
