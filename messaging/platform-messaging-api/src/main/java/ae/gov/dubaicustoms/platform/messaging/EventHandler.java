package ae.gov.dubaicustoms.platform.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a handler for events arriving on {@code destination}.
 *
 * <p>The annotated method takes a single parameter: either the deserialized payload type, or
 * {@code EventEnvelope<T>} when headers or metadata are needed. Exceptions thrown by the handler
 * are treated as a nack: {@code platform-messaging-autoconfigure}'s {@code EventHandlerRegistrar}
 * applies the transport's retry/DLQ policy ({@code dc.platform.messaging.handler.retry.*},
 * {@code dc.platform.messaging.dlq.suffix}) before giving up on the message.
 *
 * <pre>{@code
 * @EventHandler(destination = "dc.orders", eventType = "OrderPlaced")
 * void onOrderPlaced(OrderPlaced event) { ... }
 * }</pre>
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler {

    /**
     * The destination to subscribe to.
     *
     * @return the destination; never blank
     */
    String destination();

    /**
     * Restricts this handler to events of the named type; empty means "every event type on this
     * destination".
     *
     * @return the event type filter, or {@code ""} for no filter
     */
    String eventType() default "";
}
