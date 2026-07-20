package ae.gov.dubaicustoms.platform.validation.internal;

import ae.gov.dubaicustoms.platform.validation.Ulid;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

// Validator half of @Ulid: 26 Crockford base32 chars, first 0-7 (128-bit cap), case-insensitive.
public final class UlidValidator implements ConstraintValidator<Ulid, String> {

    private static final Pattern FORMAT =
            Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || FORMAT.matcher(value).matches();
    }
}
