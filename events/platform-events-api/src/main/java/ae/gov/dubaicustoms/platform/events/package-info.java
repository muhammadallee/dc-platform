/**
 * In-process domain events: {@link ae.gov.dubaicustoms.platform.events.DomainEvent} is the marker
 * type, {@link ae.gov.dubaicustoms.platform.events.DomainEventPublisher} publishes them, and
 * {@link ae.gov.dubaicustoms.platform.events.DomainEventHandler @DomainEventHandler} marks handler
 * methods. {@code platform-events-autoconfigure} supplies the implementation, bridging to Spring's
 * {@code ApplicationEventPublisher} with after-commit dispatch.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.events;
