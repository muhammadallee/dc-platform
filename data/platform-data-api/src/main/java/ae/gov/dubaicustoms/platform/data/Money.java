package ae.gov.dubaicustoms.platform.data;

import ae.gov.dubaicustoms.platform.core.PlatformApi;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * An immutable monetary amount in a single ISO-4217 currency.
 *
 * <p>Use this instead of a bare {@link BigDecimal} wherever a service models money, so the currency
 * travels with the amount and arithmetic across mismatched currencies fails loudly rather than
 * producing a silently wrong number.
 *
 * <pre>{@code
 * Money price = Money.of("19.99", "AED");
 * Money withTax = price.add(Money.of("1.00", "AED"));  // 20.99 AED
 * }</pre>
 *
 * <p><strong>Attribute-converter contract.</strong> The JPA capability persists {@code Money} to a
 * single column using the canonical string form produced by {@link #toStorageString()} — the plain
 * amount, a single space, then the ISO currency code (for example {@code "19.99 AED"}) — and reads
 * it back with {@link #parse(String)}. {@code platform-data-jpa-autoconfigure} registers the
 * matching {@code AttributeConverter}; keeping the format here (rather than in a JPA type) is what
 * lets this module stay free of persistence dependencies.
 *
 * <p>Value object; immutable and thread-safe. Components are never {@code null}.
 *
 * @param amount the monetary amount; never {@code null}
 * @param currency the ISO-4217 currency; never {@code null}
 * @since 0.2.0
 */
@PlatformApi
@API(status = API.Status.STABLE, since = "0.1.0")
public record Money(BigDecimal amount, Currency currency) {

    /**
     * Validates that both components are present.
     *
     * @throws NullPointerException if {@code amount} or {@code currency} is null
     */
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /**
     * Creates a {@code Money} from an amount and currency.
     *
     * @param amount the monetary amount; never {@code null}
     * @param currency the ISO-4217 currency; never {@code null}
     * @return the money value; never {@code null}
     */
    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /**
     * Creates a {@code Money} from a decimal string and an ISO currency code.
     *
     * @param amount the amount as a decimal string, for example {@code "19.99"}; never {@code null}
     * @param currencyCode the ISO-4217 currency code, for example {@code "AED"}; never {@code null}
     * @return the money value; never {@code null}
     * @throws NumberFormatException if {@code amount} is not a valid decimal
     * @throws IllegalArgumentException if {@code currencyCode} is not a supported ISO-4217 code
     */
    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    /**
     * Returns the sum of this and {@code other}.
     *
     * @param other the amount to add; must be the same currency; never {@code null}
     * @return a new {@code Money} with the summed amount; never {@code null}
     * @throws IllegalArgumentException if {@code other} is in a different currency
     */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Returns the difference of this and {@code other}.
     *
     * @param other the amount to subtract; must be the same currency; never {@code null}
     * @return a new {@code Money} with the reduced amount; never {@code null}
     * @throws IllegalArgumentException if {@code other} is in a different currency
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Serializes this value to the canonical storage string {@code "<amount> <currencyCode>"} used
     * by the JPA attribute converter (see the class javadoc).
     *
     * @return the storage form, for example {@code "19.99 AED"}; never {@code null}
     */
    public String toStorageString() {
        return amount.toPlainString() + ' ' + currency.getCurrencyCode();
    }

    /**
     * Parses the canonical storage string produced by {@link #toStorageString()}.
     *
     * @param stored the storage form {@code "<amount> <currencyCode>"}; never {@code null}
     * @return the reconstructed money value; never {@code null}
     * @throws IllegalArgumentException if {@code stored} is not a single amount/code pair
     */
    public static Money parse(String stored) {
        Objects.requireNonNull(stored, "stored must not be null");
        int space = stored.lastIndexOf(' ');
        if (space <= 0 || space == stored.length() - 1) {
            throw new IllegalArgumentException("not a '<amount> <currencyCode>' pair: " + stored);
        }
        return of(stored.substring(0, space), stored.substring(space + 1));
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }
}
