package ae.gov.dubaicustoms.platform.data;

import ae.gov.dubaicustoms.platform.core.PlatformApi;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * A typed wrapper around a raw entity identifier, so an {@code OrderId} cannot be passed where a
 * {@code CustomerId} is expected even when both are backed by the same raw type.
 *
 * <p>Declare a per-entity id as a small subtype or a domain-specific alias:
 *
 * <pre>{@code
 * EntityId<Long> orderId = EntityId.of(42L);
 * Long raw = orderId.value();
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe when the wrapped {@code value} is immutable (raw ids —
 * {@code Long}, {@code UUID}, {@code String} — are). The wrapped value is never {@code null}.
 *
 * @param <T> the raw identifier type, typically {@link Long}, {@link java.util.UUID}, or
 *        {@link String}
 * @param value the wrapped raw identifier; never {@code null}
 * @since 0.2.0
 */
@PlatformApi
@API(status = API.Status.STABLE, since = "0.1.0")
public record EntityId<T>(T value) {

    /**
     * Validates that the wrapped value is present.
     *
     * @throws NullPointerException if {@code value} is null
     */
    public EntityId {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * Wraps a raw identifier.
     *
     * @param value the raw identifier; never {@code null}
     * @param <T> the raw identifier type
     * @return the typed id; never {@code null}
     */
    public static <T> EntityId<T> of(T value) {
        return new EntityId<>(value);
    }
}
