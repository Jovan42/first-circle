package io.github.jovan42.banking.service;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.IdempotencyKey;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.AccountNotFoundException;
import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.DuplicateRequestException;
import io.github.jovan42.banking.exception.InsufficientFundsException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import io.github.jovan42.banking.exception.SameAccountTransferException;
import io.github.jovan42.banking.ledger.LedgerEntry;
import java.util.List;

/**
 * Public entry point for banking operations.
 *
 * <p>Deposits, withdrawals and transfers have an overload taking an {@link IdempotencyKey}; use
 * it when a request may be retried (e.g. after a network timeout) so the money moves only once.
 *
 * <p>All methods are thread-safe. Every operation either completes fully or fails with a
 * {@link io.github.jovan42.banking.exception.BankingException} and leaves balances and the ledger
 * unchanged.
 */
public interface AccountService {

    /**
     * Opens a new account. Its currency is the currency of the initial deposit.
     * A zero initial deposit is allowed.
     *
     * @throws InvalidAmountException if the initial deposit is null
     */
    AccountId openAccount(Money initialDeposit);

    /**
     * @throws AccountNotFoundException  if the account does not exist
     * @throws InvalidAmountException    if the amount is null or not positive
     * @throws CurrencyMismatchException if the amount is not in the account's currency
     */
    TransactionId deposit(AccountId accountId, Money amount);

    /**
     * Overdrafts are not allowed.
     *
     * @throws AccountNotFoundException   if the account does not exist
     * @throws InvalidAmountException     if the amount is null or not positive
     * @throws CurrencyMismatchException  if the amount is not in the account's currency
     * @throws InsufficientFundsException if the balance is lower than the amount
     */
    TransactionId withdraw(AccountId accountId, Money amount);

    /**
     * Moves money between two accounts atomically: both balances change, or neither does.
     *
     * @throws SameAccountTransferException if both ids are the same account
     * @throws AccountNotFoundException     if either account does not exist
     * @throws InvalidAmountException       if the amount is null or not positive
     * @throws CurrencyMismatchException    if the amount is not in both accounts' currency
     * @throws InsufficientFundsException   if the source balance is lower than the amount
     */
    TransactionId transfer(AccountId fromAccountId, AccountId toAccountId, Money amount);

    /**
     * Same as {@link #deposit(AccountId, Money)}, but safe to retry: the key is recorded in the
     * ledger with the transaction, and a second request with the same key is rejected.
     *
     * @throws DuplicateRequestException if the key was already used by a successful request;
     *                                   nothing is changed and the exception carries the
     *                                   original transaction id
     */
    TransactionId deposit(AccountId accountId, Money amount, IdempotencyKey key);

    /**
     * Same as {@link #withdraw(AccountId, Money)}, but safe to retry.
     *
     * @throws DuplicateRequestException if the key was already used by a successful request
     */
    TransactionId withdraw(AccountId accountId, Money amount, IdempotencyKey key);

    /**
     * Same as {@link #transfer(AccountId, AccountId, Money)}, but safe to retry.
     *
     * @throws DuplicateRequestException if the key was already used by a successful request
     */
    TransactionId transfer(AccountId fromAccountId, AccountId toAccountId, Money amount, IdempotencyKey key);

    /**
     * @throws AccountNotFoundException if the account does not exist
     */
    Money getBalance(AccountId accountId);

    /**
     * Returns the account's ledger entries, oldest first, as an immutable snapshot.
     *
     * @throws AccountNotFoundException if the account does not exist
     */
    List<LedgerEntry> getHistory(AccountId accountId);
}
