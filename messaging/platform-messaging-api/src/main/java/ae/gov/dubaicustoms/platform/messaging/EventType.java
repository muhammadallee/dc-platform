package ae.gov.dubaicustoms.platform.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Declares the logical event type and schema version for a payload class.
 *
 * <pre>{@code
 * @EventType(value = "OrderPlaced", version = 1)
 * public record OrderPlaced(String orderId) {}
 * }</pre>
 *
 * <p>{@link EventEnvelope.Builder} reads this annotation to populate {@code eventType} and
 * {@code eventVersion}; payload classes without it fall back to the simple class name and
 * version {@code 1}. Thread-safe (annotations are inert).
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface EventType {

    /**
     * The logical event type name, e.g. {@code "OrderPlaced"}.
     *
     * @return the event type; never blank
     */
    String value();

    /**
     * The schema version of the payload.
     *
     * @return the version; at least 1
     */
    int version() default 1;
}
