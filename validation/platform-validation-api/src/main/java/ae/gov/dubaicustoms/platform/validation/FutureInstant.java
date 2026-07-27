package ae.gov.dubaicustoms.platform.validation;

import ae.gov.dubaicustoms.platform.validation.internal.FutureInstantValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * The annotated {@link java.time.Instant} must lie in the future, measured against the
 * validator's {@code ClockProvider} — inject a fixed clock in tests instead of sleeping.
 * {@code null} is valid — combine with {@code @NotNull} to require presence.
 *
 * <pre>{@code
 * record Appointment(@NotNull @FutureInstant Instant scheduledAt) { }
 * }</pre>
 *
 * <p>Thread-safe (annotation). Applies to {@code Instant}.
 *
 * @since 0.1.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FutureInstantValidator.class)
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface FutureInstant {

    /** The violation message; resolves through the platform validation bundle. */
    String message() default "{dc.platform.validation.FutureInstant.message}";

    /** Validation groups. */
    Class<?>[] groups() default {};

    /** Custom payloads. */
    Class<? extends Payload>[] payload() default {};
}
