package ae.gov.dubaicustoms.platform.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void ofStringParsesAmountAndCurrency() {
        Money money = Money.of("19.99", "AED");
        assertThat(money.amount()).isEqualByComparingTo("19.99");
        assertThat(money.currency()).isEqualTo(AED);
    }

    @Test
    void addSumsSameCurrency() {
        Money sum = Money.of("19.99", "AED").add(Money.of("1.01", "AED"));
        assertThat(sum.amount()).isEqualByComparingTo("21.00");
        assertThat(sum.currency()).isEqualTo(AED);
    }

    @Test
    void subtractReducesSameCurrency() {
        Money diff = Money.of("20.00", "AED").subtract(Money.of("0.01", "AED"));
        assertThat(diff.amount()).isEqualByComparingTo("19.99");
    }

    @Test
    void addRejectsCurrencyMismatch() {
        assertThatThrownBy(() -> Money.of("1", "AED").add(Money.of("1", "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void storageRoundTrips() {
        Money original = new Money(new BigDecimal("19.99"), USD);
        assertThat(original.toStorageString()).isEqualTo("19.99 USD");
        assertThat(Money.parse(original.toStorageString())).isEqualTo(original);
    }

    @Test
    void parseRejectsMalformedInput() {
        assertThatThrownBy(() -> Money.parse("19.99"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullComponentsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null, AED));
        assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.ONE, null));
    }
}
