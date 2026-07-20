/**
 * The messaging provider contract: {@link ae.gov.dubaicustoms.platform.messaging.spi.EventTransport}
 * is implemented once per transport (in-memory, Kafka, RabbitMQ);
 * {@link ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer} is the pluggable payload
 * codec. Application code never depends on this module directly — only transport
 * implementations and {@code platform-messaging-autoconfigure} do.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.messaging.spi;
