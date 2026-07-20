package ae.gov.dubaicustoms.platform.validation;

import ae.gov.dubaicustoms.platform.validation.internal.NotBlankTrimmedValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string must contain at least one non-whitespace character; {@code null} is
 * invalid. Unlike {@code @NotBlank} the intent is explicit in the name: {@code "  "} fails, and
 * leading/trailing whitespace never counts as content.
 *
 * <pre>{@code
 * record CreateOrder(@NotBlankTrimmed String customerName) { }
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
@Constraint(validatedBy = NotBlankTrimmedValidator.class)
public @interface NotBlankTrimmed {

    /** The violation message; resolves through the platform validation bundle. */
    String message() default "{dc.platform.validation.NotBlankTrimmed.message}";

    /** Validation groups. */
    Class<?>[] groups() default {};

    /** Custom payloads. */
    Class<? extends Payload>[] payload() default {};
}
