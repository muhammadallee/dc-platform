package ae.gov.dubaicustoms.platform.messaging.rabbit;

import java.util.Objects;

/**
 * Parses the {@code EventTransport} destination syntax used by {@link RabbitEventTransport}:
 * {@code "exchange"} (empty routing key) or {@code "exchange:routingKey"}.
 *
 * @param exchange the exchange name; never {@code null}
 * @param routingKey the routing key; never {@code null}, empty when the destination carried no
 *     {@code ':'}
 */
record RabbitDestination(String exchange, String routingKey) {

    RabbitDestination {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(routingKey, "routingKey must not be null");
    }

    /**
     * Parses {@code destination} per the {@code "exchange"} / {@code "exchange:routingKey"} syntax.
     *
     * @param destination the raw destination string; never {@code null}
     * @return the parsed exchange/routing-key pair; never {@code null}
     */
    static RabbitDestination parse(String destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        int colon = destination.indexOf(':');
        return colon < 0
                ? new RabbitDestination(destination, "")
                : new RabbitDestination(destination.substring(0, colon), destination.substring(colon + 1));
    }
}
