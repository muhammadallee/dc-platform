package ae.gov.dubaicustoms.platform.validation;

import ae.gov.dubaicustoms.platform.validation.internal.UlidValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string must be a valid ULID: 26 Crockford base32 characters (no I, L, O, U),
 * first character {@code 0}–{@code 7} so the timestamp fits 128 bits. Case-insensitive, per the
 * ULID spec. {@code null} is valid — combine with {@code @NotNull} to require presence.
 *
 * <pre>{@code
 * record LookupRequest(@NotNull @Ulid String declarationId) { }
 * }</pre>
 *
 * <p>Thread-safe (annotation). Applies to {@code String}.
 *
 * @since 0.1.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UlidValidator.class)
public @interface Ulid {

    /** The violation message; resolves through the platform validation bundle. */
    String message() default "{dc.platform.validation.Ulid.message}";

    /** Validation groups. */
    Class<?>[] groups() default {};

    /** Custom payloads. */
    Class<? extends Payload>[] payload() default {};
}
