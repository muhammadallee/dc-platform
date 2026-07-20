package ae.gov.dubaicustoms.platform.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a handler for {@link DomainEvent}s of its single parameter's exact runtime
 * type. {@code platform-events-autoconfigure}'s registrar dispatches after the enclosing
 * transaction commits when one is active at publish time, immediately otherwise.
 *
 * <pre>{@code
 * @DomainEventHandler
 * void onOrderPlaced(OrderPlaced event) { ... }
 * }</pre>
 *
 * <p>The annotated method must declare exactly one parameter: the concrete {@link DomainEvent}
 * type it handles.
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DomainEventHandler {
}
