package io.github.jovan42.banking.service;

import io.github.jovan42.banking.domain.Account;
import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.AccountNotFoundException;
import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import io.github.jovan42.banking.exception.SameAccountTransferException;
import io.github.jovan42.banking.ledger.Ledger;
import io.github.jovan42.banking.ledger.LedgerEntry;
import io.github.jovan42.banking.repository.AccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Default {@link AccountService}: coordinates accounts, the ledger and the clock.
 *
 * <p><b>Locking.</b> Each operation holds the lock of every account it changes while it updates
 * the balance <i>and</i> appends the ledger entry, so an account's ledger order always matches the
 * order its balance changed. Transfers take the two locks in {@link AccountId} order, so two
 * opposite transfers (A to B and B to A) cannot deadlock.
 *
 * <p><b>Failure atomicity.</b> There is no rollback in memory, so every operation is ordered so
 * that nothing can fail after the first change:
 * <ol>
 *   <li>get the transaction id and timestamp (the injected clock could fail);</li>
 *   <li>under the locks, run every check and compute the new balances without applying them;</li>
 *   <li>record the ledger entries (a transfer's two sides in one {@code appendAll});</li>
 *   <li>apply the balances, which can no longer fail.</li>
 * </ol>
 * A failure at any step leaves both the balances and the ledger unchanged. This is the same
 * write-ahead idea databases use: record what you are about to do, then do it.
 */
public final class DefaultAccountService implements AccountService {

    private final AccountRepository accounts;
    private final Ledger ledger;
    private final Clock clock;

    public DefaultAccountService(AccountRepository accounts, Ledger ledger, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AccountId openAccount(Money initialDeposit) {
        if (initialDeposit == null) {
            throw new InvalidAmountException("Initial deposit must not be null");
        }
        Account account = Account.open(AccountId.newId(), initialDeposit);
        // Nobody else knows the new id yet, so no lock is needed.
        // Record first, then publish: if the ledger fails, the account never becomes visible.
        // A zero opening balance has nothing to record (ledger amounts are always positive).
        if (initialDeposit.isPositive()) {
            ledger.append(LedgerEntry.deposit(
                    TransactionId.newId(), account.getId(), initialDeposit, initialDeposit, clock.instant()));
        }
        accounts.add(account);
        return account.getId();
    }

    @Override
    public TransactionId deposit(AccountId accountId, Money amount) {
        Account account = find(accountId);
        TransactionId txId = TransactionId.newId();
        Instant now = clock.instant();

        account.getLock().lock();
        try {
            Money balanceAfter = account.previewDeposit(amount);      // 1. every check, no change
            ledger.append(LedgerEntry.deposit(txId, accountId, amount, balanceAfter, now));  // 2. record
            account.deposit(amount);                                   // 3. apply; cannot fail now
            return txId;
        } finally {
            account.getLock().unlock();
        }
    }

    @Override
    public TransactionId withdraw(AccountId accountId, Money amount) {
        Account account = find(accountId);
        TransactionId txId = TransactionId.newId();
        Instant now = clock.instant();

        account.getLock().lock();
        try {
            Money balanceAfter = account.previewWithdrawal(amount);   // 1. every check, no change
            ledger.append(LedgerEntry.withdrawal(txId, accountId, amount, balanceAfter, now));  // 2. record
            account.withdraw(amount);                                  // 3. apply; cannot fail now
            return txId;
        } finally {
            account.getLock().unlock();
        }
    }

    @Override
    public TransactionId transfer(AccountId fromAccountId, AccountId toAccountId, Money amount) {
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        if (fromAccountId.equals(toAccountId)) {
            throw new SameAccountTransferException(fromAccountId);
        }
        Account from = find(fromAccountId);
        Account to = find(toAccountId);

        // Fail fast on bad input before taking any locks.
        requirePositive(amount);
        requireCurrency(from, amount);
        requireCurrency(to, amount);

        // Anything that touches an injected dependency happens before the first change.
        TransactionId txId = TransactionId.newId();
        Instant now = clock.instant();

        // Lock in a global order (by id) so A->B and B->A cannot wait on each other forever.
        Account first = fromAccountId.compareTo(toAccountId) < 0 ? from : to;
        Account second = first == from ? to : from;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                // 1. Every check, no change. Both locks are held, so the results stay valid.
                Money fromBalanceAfter = from.previewWithdrawal(amount);
                Money toBalanceAfter = to.previewDeposit(amount);

                // 2. Record both sides as one unit. If this fails, nothing has changed.
                ledger.appendAll(List.of(
                        LedgerEntry.transferOut(txId, fromAccountId, toAccountId, amount, fromBalanceAfter, now),
                        LedgerEntry.transferIn(txId, toAccountId, fromAccountId, amount, toBalanceAfter, now)));

                // 3. Apply. Every check has passed under the locks, so these cannot fail.
                from.withdraw(amount);
                to.deposit(amount);
                return txId;
            } finally {
                second.getLock().unlock();
            }
        } finally {
            first.getLock().unlock();
        }
    }

    @Override
    public Money getBalance(AccountId accountId) {
        return find(accountId).getBalance();
    }

    @Override
    public List<LedgerEntry> getHistory(AccountId accountId) {
        find(accountId);  // unknown accounts are an error, not an empty history
        return ledger.entriesFor(accountId);
    }

    private Account find(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static void requirePositive(Money amount) {
        if (amount == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Amount must be positive: " + amount);
        }
    }

    private static void requireCurrency(Account account, Money amount) {
        if (!account.getCurrency().equals(amount.currency())) {
            throw new CurrencyMismatchException(account.getCurrency(), amount.currency());
        }
    }
}
