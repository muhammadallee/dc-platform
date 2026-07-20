package ae.gov.dubaicustoms.platform.validation;

import ae.gov.dubaicustoms.platform.validation.internal.SafeTextValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string must not contain ISO control characters (including tab, newline, and
 * escape sequences) — the cheap first line of defense against log injection and terminal-escape
 * smuggling in single-line text fields. {@code null} is valid — combine with {@code @NotNull}
 * to require presence.
 *
 * <pre>{@code
 * record Remark(@SafeText String text) { }
 * }</pre>
 *
 * <p>Thread-safe (annotation). Applies to {@code String}. Not an HTML/XSS sanitizer — output
 * encoding remains the consumer's job.
 *
 * @since 0.1.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SafeTextValidator.class)
public @interface SafeText {

    /** The violation message; resolves through the platform validation bundle. */
    String message() default "{dc.platform.validation.SafeText.message}";

    /** Validation groups. */
    Class<?>[] groups() default {};

    /** Custom payloads. */
    Class<? extends Payload>[] payload() default {};
}
