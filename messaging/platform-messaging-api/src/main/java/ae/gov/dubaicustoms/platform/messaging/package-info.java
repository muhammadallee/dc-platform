/**
 * The messaging capability contract: {@link ae.gov.dubaicustoms.platform.messaging.EventPublisher}
 * publishes {@link ae.gov.dubaicustoms.platform.messaging.EventEnvelope}s, handler methods are
 * marked with {@link ae.gov.dubaicustoms.platform.messaging.EventHandler @EventHandler}, and
 * payload classes declare their logical type with
 * {@link ae.gov.dubaicustoms.platform.messaging.EventType @EventType}.
 *
 * <p>Applications depend only on this module; {@code platform-messaging-spi} defines the provider
 * contract, and {@code platform-messaging-autoconfigure} supplies the implementation over
 * whichever {@code EventTransport} is on the classpath (in-memory, Kafka, or RabbitMQ).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.messaging;
