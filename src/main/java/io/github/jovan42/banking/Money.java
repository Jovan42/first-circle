package io.github.jovan42.banking;

import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable, non-negative amount of money in a single currency.
 *
 * <p>The amount is always stored at the currency's standard scale (e.g. 2 for EUR, 0 for JPY),
 * so {@code 10}, {@code 10.0} and {@code 10.00} EUR are the same value and {@link #equals}
 * is safe to use.
 *
 * <p>Amounts with more decimal places than the currency allows are rejected rather than
 * rounded: silently changing the amount a customer asked for is worse than refusing it.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        Objects.requireNonNull(currency, "currency");

        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
        if (amount.signum() < 0) {
            throw new InvalidAmountException("Amount must not be negative: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new InvalidAmountException(
                    "Amount " + amount.toPlainString() + " has more than " + scale
                            + " decimal places allowed for " + currency);
        }
        // Exact: the check above guarantees no digits are lost.
        amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, Currency currency) {
        if (amount == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        try {
            return new Money(new BigDecimal(amount), currency);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("Not a valid amount: " + amount);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Subtracts {@code other} from this amount.
     *
     * @throws IllegalArgumentException if the result would be negative; callers are expected
     *         to check with {@link #isLessThan} first and raise a domain error themselves
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        if (amount.compareTo(other.amount) < 0) {
            throw new IllegalArgumentException("Result would be negative: " + this + " - " + other);
        }
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
