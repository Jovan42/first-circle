package io.github.jovan42.banking.exception;

import io.github.jovan42.banking.AccountId;
import io.github.jovan42.banking.Money;

/**
 * Thrown when a withdrawal or transfer would take an account below zero.
 * Overdrafts are not allowed.
 */
public final class InsufficientFundsException extends BankingException {

    private final AccountId accountId;
    private final Money balance;
    private final Money requested;

    public InsufficientFundsException(AccountId accountId, Money balance, Money requested) {
        super("Insufficient funds in account " + accountId
                + ": balance " + balance + ", requested " + requested);
        this.accountId = accountId;
        this.balance = balance;
        this.requested = requested;
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public Money getBalance() {
        return balance;
    }

    public Money getRequested() {
        return requested;
    }
}
