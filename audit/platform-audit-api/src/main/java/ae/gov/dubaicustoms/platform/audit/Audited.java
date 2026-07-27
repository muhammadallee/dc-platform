package ae.gov.dubaicustoms.platform.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Marks a method whose invocation is recorded as an {@link AuditEvent}. The platform intercepts the
 * call, derives the actor from the current user and the correlation id from the request context,
 * evaluates {@link #resourceExpression()} against the invocation, sets the {@link Outcome} from the
 * method's return or thrown exception, and hands the event to the configured
 * {@link ae.gov.dubaicustoms.platform.audit.spi.AuditSink AuditSink}.
 *
 * <pre>{@code
 * @Audited(action = "order.create", resourceExpression = "#result.id")
 * public Order create(CreateOrderCommand command) { ... }
 * }</pre>
 *
 * <p>Apply to public methods of Spring-managed beans. The event is recorded whether the method
 * returns or throws — a failure is audited with {@link Outcome#FAILURE} and the exception re-thrown
 * unchanged.
 *
 * @since 0.2.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface Audited {

    /**
     * The logical action name recorded on the event, e.g. {@code order.create}.
     *
     * @return the action name
     */
    String action();

    /**
     * A SpEL expression, evaluated against the invocation, yielding the {@code resource} the action
     * targets. The method arguments are bound by name and as {@code #a0}, {@code #p0}, …; the return
     * value is bound as {@code #result} (available only on success). Empty means no resource is
     * recorded.
     *
     * @return the resource expression, or {@code ""} for none
     */
    String resourceExpression() default "";
}
