package io.github.jovan42.banking.exception;

/**
 * Base type for all domain errors raised by the banking service.
 * Unchecked, so callers can handle the specific failures they care about.
 */
public abstract class BankingException extends RuntimeException {

    protected BankingException(String message) {
        super(message);
    }
}
