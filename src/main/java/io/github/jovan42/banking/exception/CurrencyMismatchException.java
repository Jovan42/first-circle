package io.github.jovan42.banking.exception;

import java.util.Currency;

/**
 * Thrown when an operation combines amounts in different currencies.
 * Currency conversion is out of scope.
 */
public final class CurrencyMismatchException extends BankingException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected " + expected + " but got " + actual);
    }
}
