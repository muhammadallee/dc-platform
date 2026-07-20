package ae.gov.dubaicustoms.platform.validation.internal;

import ae.gov.dubaicustoms.platform.validation.NotBlankTrimmed;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Validator half of @NotBlankTrimmed; null is invalid to match @NotBlank semantics.
public final class NotBlankTrimmedValidator implements ConstraintValidator<NotBlankTrimmed, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && !value.strip().isEmpty();
    }
}
