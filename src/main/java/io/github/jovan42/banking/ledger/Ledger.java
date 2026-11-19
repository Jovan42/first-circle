package io.github.jovan42.banking.ledger;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.IdempotencyKey;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.DuplicateRequestException;
import java.util.List;
import java.util.Optional;

/**
 * Append-only history of balance changes.
 *
 * <p>Entries can only be added, never changed or removed. Implementations must be thread-safe.
 *
 * <p>An idempotency key can be recorded only once: appending entries whose key is already in the
 * ledger fails with {@link DuplicateRequestException} and records nothing. This is the in-memory
 * equivalent of a unique constraint on an idempotency-key column.
 */
public interface Ledger {

    /**
     * Appends one entry.
     *
     * @throws DuplicateRequestException if the entry's idempotency key is already recorded
     */
    void append(LedgerEntry entry);

    /**
     * Appends several entries of one transaction as one unit, in order: either all of them are
     * recorded or none is. Used for transfers, whose two sides must never be recorded separately.
     *
     * @throws DuplicateRequestException if the entries' idempotency key is already recorded
     * @throws IllegalArgumentException  if the entries belong to different transactions
     */
    void appendAll(List<LedgerEntry> entries);

    /**
     * Returns the entries for an account, oldest first, as an immutable snapshot.
     * Returns an empty list for an account with no entries.
     */
    List<LedgerEntry> entriesFor(AccountId accountId);

    /** Returns the transaction recorded under an idempotency key, if any. */
    Optional<TransactionId> findTransaction(IdempotencyKey key);
}
