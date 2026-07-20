package ae.gov.dubaicustoms.platform.validation.internal;

import ae.gov.dubaicustoms.platform.validation.FutureInstant;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Instant;

// Validator half of @FutureInstant; reads the engine's ClockProvider so tests inject fixed time.
public final class FutureInstantValidator implements ConstraintValidator<FutureInstant, Instant> {

    @Override
    public boolean isValid(Instant value, ConstraintValidatorContext context) {
        return value == null
                || value.isAfter(Instant.now(context.getClockProvider().getClock()));
    }
}
