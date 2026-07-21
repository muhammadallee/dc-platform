package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a {@link CorrelationId} to a single column as its 32-hex-character text.
 *
 * <p>Opt in per field with {@code @Convert(converter = CorrelationIdConverter.class)} (see
 * {@link MoneyConverter} for why library converters are not auto-applied).
 */
@Converter
public class CorrelationIdConverter implements AttributeConverter<CorrelationId, String> {

    @Override
    public String convertToDatabaseColumn(CorrelationId attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public CorrelationId convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new CorrelationId(dbData);
    }
}
