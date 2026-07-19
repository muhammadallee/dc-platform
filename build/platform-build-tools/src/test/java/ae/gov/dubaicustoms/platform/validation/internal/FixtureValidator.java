package ae.gov.dubaicustoms.platform.validation.internal;

import ae.gov.dubaicustoms.platform.validation.ValidationConstraintFixture;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Fixture: validator half of ValidationConstraintFixture; internals are exempt from the api rule.
public final class FixtureValidator implements ConstraintValidator<ValidationConstraintFixture, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null;
    }
}
