package io.github.jovan42.banking;

import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InsufficientFundsException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.util.Currency;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bank account holding a balance in a single currency.
 *
 * <p>Thread-safe: every read and write of the balance happens while holding the account's lock,
 * so a balance check and the update that depends on it are atomic (no check-then-act race).
 *
 * <p>The lock is reentrant and exposed to the package so that a transfer can hold the locks of
 * both accounts at once and still call {@link #deposit} / {@link #withdraw}.
 *
 * <p>Package-private: callers interact with accounts through the service, never directly.
 */
final class Account {

    private final AccountId id;
    private final Currency currency;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock.
    private Money balance;

    private Account(AccountId id, Money initialBalance) {
        this.id = Objects.requireNonNull(id, "id");
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance");
        this.currency = initialBalance.currency();
    }

    /**
     * Opens an account whose currency is taken from the initial deposit.
     * A zero initial deposit is allowed; negative amounts cannot be expressed as {@link Money}.
     */
    static Account open(AccountId id, Money initialDeposit) {
        return new Account(id, initialDeposit);
    }

    AccountId getId() {
        return id;
    }

    Currency getCurrency() {
        return currency;
    }

    ReentrantLock getLock() {
        return lock;
    }

    Money getBalance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds {@code amount} to the balance.
     *
     * @return the new balance
     * @throws InvalidAmountException    if the amount is not positive
     * @throws CurrencyMismatchException if the amount is not in the account's currency
     */
    Money deposit(Money amount) {
        validate(amount);
        lock.lock();
        try {
            balance = balance.plus(amount);
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes {@code amount} from the balance. Overdrafts are not allowed.
     *
     * @return the new balance
     * @throws InvalidAmountException     if the amount is not positive
     * @throws CurrencyMismatchException  if the amount is not in the account's currency
     * @throws InsufficientFundsException if the balance is lower than the amount;
     *                                    the balance is left unchanged
     */
    Money withdraw(Money amount) {
        validate(amount);
        lock.lock();
        try {
            if (balance.isLessThan(amount)) {
                throw new InsufficientFundsException(id, balance, amount);
            }
            balance = balance.minus(amount);
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /** Validation needs no lock: it only looks at the immutable amount and currency. */
    private void validate(Money amount) {
        if (amount == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        if (!amount.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Amount must be positive: " + amount);
        }
    }

    @Override
    public String toString() {
        return "Account[" + id + ", " + currency.getCurrencyCode() + "]";
    }
}
