package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.data.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link Money} to a single {@code VARCHAR} column using its canonical storage form
 * {@code "<amount> <currencyCode>"}.
 *
 * <p>Opt in per field with {@code @Convert(converter = MoneyConverter.class)} (not auto-applied — a
 * library converter is not part of the application's scanned persistence unit, so global
 * auto-apply would not fire anyway; explicit {@code @Convert} is unambiguous and portable).
 */
@Converter
public class MoneyConverter implements AttributeConverter<Money, String> {

    @Override
    public String convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.toStorageString();
    }

    @Override
    public Money convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Money.parse(dbData);
    }
}
