package io.github.jovan42.banking.exception;

import io.github.jovan42.banking.AccountId;

/**
 * Thrown when an operation refers to an account that does not exist.
 */
public final class AccountNotFoundException extends BankingException {

    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
