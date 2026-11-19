package io.github.jovan42.banking.exception;

import io.github.jovan42.banking.AccountId;

/**
 * Thrown when a transfer names the same account as source and destination.
 */
public final class SameAccountTransferException extends BankingException {

    private final AccountId accountId;

    public SameAccountTransferException(AccountId accountId) {
        super("Cannot transfer from an account to itself: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
