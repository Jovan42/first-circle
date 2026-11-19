package io.github.jovan42.banking.ledger;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable record of one balance change on one account.
 *
 * <p>A transfer produces two entries (one {@link EntryType#TRANSFER_OUT}, one
 * {@link EntryType#TRANSFER_IN}) that share the same {@link TransactionId} and name each other's
 * account as counterparty.
 *
 * <p>Prefer the factory methods ({@link #deposit}, {@link #withdrawal}, {@link #transferOut},
 * {@link #transferIn}); the constructor enforces the same rules.
 *
 * @param transactionId         the operation this entry belongs to
 * @param accountId             the account whose balance changed
 * @param type                  what kind of change it was
 * @param amount                the amount moved, always positive
 * @param balanceAfter          the account's balance immediately after this change
 * @param counterpartyAccountId the other account in a transfer; empty for deposits and withdrawals
 * @param timestamp             when the change happened
 */
public record LedgerEntry(
        TransactionId transactionId,
        AccountId accountId,
        EntryType type,
        Money amount,
        Money balanceAfter,
        Optional<AccountId> counterpartyAccountId,
        Instant timestamp) {

    public LedgerEntry {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(counterpartyAccountId, "counterpartyAccountId");
        Objects.requireNonNull(timestamp, "timestamp");

        if (!amount.isPositive()) {
            throw new InvalidAmountException("Ledger entry amount must be positive: " + amount);
        }
        if (!amount.currency().equals(balanceAfter.currency())) {
            throw new CurrencyMismatchException(balanceAfter.currency(), amount.currency());
        }

        boolean isTransfer = type == EntryType.TRANSFER_IN || type == EntryType.TRANSFER_OUT;
        if (isTransfer && counterpartyAccountId.isEmpty()) {
            throw new IllegalArgumentException(type + " entry requires a counterparty account");
        }
        if (!isTransfer && counterpartyAccountId.isPresent()) {
            throw new IllegalArgumentException(type + " entry must not have a counterparty account");
        }
        if (counterpartyAccountId.filter(accountId::equals).isPresent()) {
            throw new IllegalArgumentException("Counterparty must differ from the entry's own account");
        }
    }

    public static LedgerEntry deposit(
            TransactionId transactionId, AccountId accountId, Money amount, Money balanceAfter, Instant timestamp) {
        return new LedgerEntry(transactionId, accountId, EntryType.DEPOSIT,
                amount, balanceAfter, Optional.empty(), timestamp);
    }

    public static LedgerEntry withdrawal(
            TransactionId transactionId, AccountId accountId, Money amount, Money balanceAfter, Instant timestamp) {
        return new LedgerEntry(transactionId, accountId, EntryType.WITHDRAWAL,
                amount, balanceAfter, Optional.empty(), timestamp);
    }

    /** The debit side of a transfer: money leaving {@code accountId} for {@code toAccountId}. */
    public static LedgerEntry transferOut(
            TransactionId transactionId, AccountId accountId, AccountId toAccountId,
            Money amount, Money balanceAfter, Instant timestamp) {
        return new LedgerEntry(transactionId, accountId, EntryType.TRANSFER_OUT,
                amount, balanceAfter, Optional.of(toAccountId), timestamp);
    }

    /** The credit side of a transfer: money arriving in {@code accountId} from {@code fromAccountId}. */
    public static LedgerEntry transferIn(
            TransactionId transactionId, AccountId accountId, AccountId fromAccountId,
            Money amount, Money balanceAfter, Instant timestamp) {
        return new LedgerEntry(transactionId, accountId, EntryType.TRANSFER_IN,
                amount, balanceAfter, Optional.of(fromAccountId), timestamp);
    }
}
