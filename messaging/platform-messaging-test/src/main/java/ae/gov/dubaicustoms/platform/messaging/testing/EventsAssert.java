package ae.gov.dubaicustoms.platform.messaging.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;

/**
 * AssertJ-style assertions over a {@link TestEventTransport}'s recorded {@link TestEventTransport#sent()}.
 *
 * <pre>{@code
 * assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
 * }</pre>
 *
 * <p>Not thread-safe; each instance is scoped to one assertion chain.
 *
 * @since 0.2.0
 */
public final class EventsAssert {

    private final List<TestEventTransport.SentEvent> sent;
    private String destination;

    private EventsAssert(List<TestEventTransport.SentEvent> sent) {
        this.sent = sent;
    }

    /**
     * Starts an assertion chain over {@code transport}'s recorded sends.
     *
     * @param transport the transport to assert against; never {@code null}
     * @return a new assertion; never {@code null}
     */
    public static EventsAssert assertThatEvents(TestEventTransport transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        return new EventsAssert(transport.sent());
    }

    /**
     * Asserts at least one event was sent to {@code destination}, and scopes subsequent
     * assertions in this chain to that destination.
     *
     * @param destination the destination to assert against; never {@code null}
     * @return this assertion, scoped to {@code destination}
     */
    public EventsAssert sentTo(String destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        this.destination = destination;
        assertThat(sent.stream().anyMatch(event -> destination.equals(event.destination())))
                .as("expected an event sent to destination '%s', but none were; sent: %s", destination, sent)
                .isTrue();
        return this;
    }

    /**
     * Asserts an event sent to the destination set by {@link #sentTo(String)} carries an
     * {@code eventType} header equal to {@code eventType}.
     *
     * @param eventType the expected event type; never {@code null}
     * @return this assertion
     * @throws IllegalStateException if {@link #sentTo(String)} was not called first
     */
    public EventsAssert withType(String eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (destination == null) {
            throw new IllegalStateException("call sentTo(destination) before withType(eventType)");
        }
        assertThat(sent.stream().anyMatch(event ->
                        destination.equals(event.destination()) && eventType.equals(event.headers().get("eventType"))))
                .as("expected an event of type '%s' sent to '%s', but none were; sent: %s", eventType, destination, sent)
                .isTrue();
        return this;
    }
}
