package ae.gov.dubaicustoms.platform.events;

import org.apiguardian.api.API;

/**
 * Marker for an in-process domain event: something that happened inside this service that other
 * parts of the same service (or, via the messaging relay, other services) may care about.
 *
 * <pre>{@code
 * public record OrderPlaced(String orderId) implements DomainEvent {}
 * }</pre>
 *
 * <p>Implementations should be immutable value types. Thread-safety of an implementation is the
 * implementation's own responsibility; the platform never mutates a published event.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface DomainEvent {
}
