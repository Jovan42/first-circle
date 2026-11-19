package io.github.jovan42.banking.ledger;

import io.github.jovan42.banking.domain.AccountId;
import java.util.List;

/**
 * Append-only history of balance changes.
 *
 * <p>Entries can only be added, never changed or removed. Implementations must be thread-safe.
 */
public interface Ledger {

    void append(LedgerEntry entry);

    /**
     * Appends several entries as one unit, in order: either all of them are recorded or none is.
     * Used for transfers, whose two sides must never be recorded separately.
     */
    void appendAll(List<LedgerEntry> entries);

    /**
     * Returns the entries for an account, oldest first, as an immutable snapshot.
     * Returns an empty list for an account with no entries.
     */
    List<LedgerEntry> entriesFor(AccountId accountId);
}
