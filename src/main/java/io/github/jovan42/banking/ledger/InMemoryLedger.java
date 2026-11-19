package io.github.jovan42.banking.ledger;

import io.github.jovan42.banking.domain.AccountId;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe in-memory {@link Ledger}, keeping a separate queue of entries per account.
 *
 * <p>Entries for one account are appended while that account's lock is held, so their order
 * matches the order the balance changed.
 */
public final class InMemoryLedger implements Ledger {

    private final ConcurrentHashMap<AccountId, Queue<LedgerEntry>> entriesByAccount =
            new ConcurrentHashMap<>();

    @Override
    public void append(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entriesByAccount
                .computeIfAbsent(entry.accountId(), id -> new ConcurrentLinkedQueue<>())
                .add(entry);
    }

    @Override
    public List<LedgerEntry> entriesFor(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Queue<LedgerEntry> entries = entriesByAccount.get(accountId);
        return entries == null ? List.of() : List.copyOf(entries);
    }
}
