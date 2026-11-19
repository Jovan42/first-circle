package io.github.jovan42.banking.exception;

/**
 * Thrown when an amount is missing, negative, zero where a positive value is required,
 * or more precise than its currency allows.
 */
public final class InvalidAmountException extends BankingException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
