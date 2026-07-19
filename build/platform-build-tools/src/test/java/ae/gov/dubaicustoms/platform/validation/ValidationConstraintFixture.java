package ae.gov.dubaicustoms.platform.validation;

import ae.gov.dubaicustoms.platform.validation.internal.FixtureValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Fixture: the validation api root package MAY use jakarta.validation and its own .internal
// validator (the Bean Validation constraint pattern).
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FixtureValidator.class)
public @interface ValidationConstraintFixture {
    String message() default "invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
