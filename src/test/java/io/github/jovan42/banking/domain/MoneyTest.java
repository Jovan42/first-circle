package io.github.jovan42.banking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    class Creation {

        @Test
        void normalisesToCurrencyScale() {
            assertThat(Money.of("10", EUR).amount()).isEqualByComparingTo("10.00");
            assertThat(Money.of("10", EUR).amount().scale()).isEqualTo(2);
            assertThat(Money.of("500", JPY).amount().scale()).isZero();
        }

        @Test
        void equalAmountsWithDifferentScalesAreEqual() {
            assertThat(Money.of("10", EUR))
                    .isEqualTo(Money.of("10.0", EUR))
                    .isEqualTo(Money.of("10.00", EUR))
                    .hasSameHashCodeAs(Money.of("10.000", EUR));
        }

        @Test
        void sameAmountInDifferentCurrenciesIsNotEqual() {
            assertThat(Money.of("10", EUR)).isNotEqualTo(Money.of("10", USD));
        }

        @Test
        void createsFromBigDecimal() {
            assertThat(Money.of(new BigDecimal("10.5"), EUR)).isEqualTo(Money.of("10.50", EUR));
        }

        @Test
        void acceptsScientificNotation() {
            assertThat(Money.of(new BigDecimal("1E+2"), EUR)).isEqualTo(Money.of("100.00", EUR));
        }

        @Test
        void acceptsTrailingZerosBeyondCurrencyScale() {
            assertThat(Money.of("10.000000", EUR)).isEqualTo(Money.of("10", EUR));
        }

        @Test
        void allowsZero() {
            assertThat(Money.zero(EUR).isZero()).isTrue();
            assertThat(Money.zero(EUR).isPositive()).isFalse();
        }

        @Test
        void treatsNegativeZeroAsZero() {
            assertThat(Money.of("-0.00", EUR)).isEqualTo(Money.zero(EUR));
        }

        @Test
        void positiveAmountIsPositive() {
            assertThat(Money.of("0.01", EUR).isPositive()).isTrue();
            assertThat(Money.of("0.01", EUR).isZero()).isFalse();
        }

        @Test
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> Money.of("-0.01", EUR))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"10.001", "0.005"})
        void rejectsMorePrecisionThanCurrencyAllows(String amount) {
            assertThatThrownBy(() -> Money.of(amount, EUR))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("decimal places");
        }

        @Test
        void rejectsFractionalYen() {
            assertThatThrownBy(() -> Money.of("1.5", JPY))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsNullAmount() {
            assertThatThrownBy(() -> Money.of((BigDecimal) null, EUR))
                    .isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> Money.of((String) null, EUR))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsUnparseableAmount() {
            assertThatThrownBy(() -> Money.of("ten", EUR))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsNullCurrency() {
            assertThatThrownBy(() -> Money.of("10", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsCurrencyWithoutStandardScale() {
            Currency noScale = Currency.getInstance("XXX");
            assertThatThrownBy(() -> Money.of("10", noScale))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsAmounts() {
            assertThat(Money.of("10.25", EUR).plus(Money.of("0.75", EUR)))
                    .isEqualTo(Money.of("11.00", EUR));
        }

        @Test
        void subtractsAmounts() {
            assertThat(Money.of("10.00", EUR).minus(Money.of("0.01", EUR)))
                    .isEqualTo(Money.of("9.99", EUR));
        }

        @Test
        void subtractingExactAmountGivesZero() {
            assertThat(Money.of("10", EUR).minus(Money.of("10", EUR)).isZero()).isTrue();
        }

        @Test
        void subtractionBelowZeroIsRejected() {
            assertThatThrownBy(() -> Money.of("10", EUR).minus(Money.of("10.01", EUR)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void doesNotMutateOperands() {
            Money a = Money.of("10", EUR);
            Money b = Money.of("5", EUR);
            a.plus(b);
            a.minus(b);
            assertThat(a).isEqualTo(Money.of("10", EUR));
            assertThat(b).isEqualTo(Money.of("5", EUR));
        }

        @Test
        void avoidsFloatingPointErrors() {
            assertThat(Money.of("0.10", EUR).plus(Money.of("0.20", EUR)))
                    .isEqualTo(Money.of("0.30", EUR));
        }

        @Test
        void rejectsNullOperand() {
            Money ten = Money.of("10", EUR);
            assertThatThrownBy(() -> ten.plus(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ten.minus(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ten.isLessThan(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsMixedCurrencies() {
            Money eur = Money.of("10", EUR);
            Money usd = Money.of("10", USD);
            assertThatThrownBy(() -> eur.plus(usd)).isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> eur.minus(usd)).isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> eur.isLessThan(usd)).isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    class Comparison {

        @Test
        void comparesAmounts() {
            assertThat(Money.of("9.99", EUR).isLessThan(Money.of("10", EUR))).isTrue();
            assertThat(Money.of("10", EUR).isLessThan(Money.of("10.00", EUR))).isFalse();
            assertThat(Money.of("10.01", EUR).isLessThan(Money.of("10", EUR))).isFalse();
        }
    }

    @Test
    void formatsWithCurrencyCode() {
        assertThat(Money.of("1234.5", EUR)).hasToString("1234.50 EUR");
    }
}
