package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.data.Money;
import org.junit.jupiter.api.Test;

class ConvertersTest {

    private final MoneyConverter money = new MoneyConverter();
    private final CorrelationIdConverter correlation = new CorrelationIdConverter();

    @Test
    void moneyRoundTrips() {
        Money value = Money.of("19.99", "AED");
        String column = money.convertToDatabaseColumn(value);
        assertThat(column).isEqualTo("19.99 AED");
        assertThat(money.convertToEntityAttribute(column)).isEqualTo(value);
    }

    @Test
    void moneyHandlesNull() {
        assertThat(money.convertToDatabaseColumn(null)).isNull();
        assertThat(money.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void correlationIdRoundTrips() {
        CorrelationId id = CorrelationId.random();
        String column = correlation.convertToDatabaseColumn(id);
        assertThat(column).isEqualTo(id.value());
        assertThat(correlation.convertToEntityAttribute(column)).isEqualTo(id);
    }

    @Test
    void correlationIdHandlesNull() {
        assertThat(correlation.convertToDatabaseColumn(null)).isNull();
        assertThat(correlation.convertToEntityAttribute(null)).isNull();
    }
}
