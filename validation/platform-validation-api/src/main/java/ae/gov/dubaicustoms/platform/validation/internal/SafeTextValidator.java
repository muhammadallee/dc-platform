package ae.gov.dubaicustoms.platform.validation.internal;

import ae.gov.dubaicustoms.platform.validation.SafeText;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Validator half of @SafeText; rejects every ISO control char (incl. tab/newline - single-line
// fields only) as the cheap defense against log injection and terminal-escape smuggling.
public final class SafeTextValidator implements ConstraintValidator<SafeText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.chars().noneMatch(Character::isISOControl);
    }
}
