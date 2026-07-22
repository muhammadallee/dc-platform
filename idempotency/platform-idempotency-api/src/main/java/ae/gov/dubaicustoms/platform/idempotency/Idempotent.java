package ae.gov.dubaicustoms.platform.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose effect must happen at most once per logical request within a time window. The
 * platform derives a key from {@link #keyExpression()} (SpEL over the method arguments), records it in
 * the {@link IdempotencyStore}, and rejects a second invocation carrying the same key while the record
 * is live — surfaced as HTTP 409 Conflict when the errors capability is present.
 *
 * <pre>{@code
 * @Idempotent(keyExpression = "#command.orderId", ttl = "24h")
 * public void placeOrder(PlaceOrderCommand command) { ... }
 * }</pre>
 *
 * <p>Response replay is out of scope: a duplicate is rejected, not answered with the first call's
 * result. Apply to public methods of Spring-managed beans.
 *
 * @since 0.2.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * A SpEL expression, evaluated against the method arguments (by name and as {@code #a0}, {@code
     * #p0}, …), that yields the idempotency key. Two invocations producing the same key are duplicates.
     *
     * @return the key expression
     */
    String keyExpression();

    /**
     * How long the key is remembered, as a duration string (e.g. {@code "24h"}, {@code "PT30M"}). A
     * duplicate arriving after the TTL elapses is treated as a fresh request.
     *
     * @return the retention duration
     */
    String ttl() default "24h";
}
