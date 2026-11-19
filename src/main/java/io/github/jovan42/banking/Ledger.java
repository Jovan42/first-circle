package io.github.jovan42.banking;

import java.util.List;

/**
 * Append-only history of balance changes.
 *
 * <p>Entries can only be added, never changed or removed. Implementations must be thread-safe.
 */
interface Ledger {

    void append(LedgerEntry entry);

    /**
     * Returns the entries for an account, oldest first, as an immutable snapshot.
     * Returns an empty list for an account with no entries.
     */
    List<LedgerEntry> entriesFor(AccountId accountId);
}
