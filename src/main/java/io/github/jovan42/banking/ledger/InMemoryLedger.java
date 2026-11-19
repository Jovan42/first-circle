package io.github.jovan42.banking.ledger;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.IdempotencyKey;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.DuplicateRequestException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe in-memory {@link Ledger}, keeping a separate queue of entries per account and an
 * index of idempotency keys.
 *
 * <p>Entries for one account are appended while that account's lock is held, so their order
 * matches the order the balance changed.
 */
public final class InMemoryLedger implements Ledger {

    private final ConcurrentHashMap<AccountId, Queue<LedgerEntry>> entriesByAccount =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdempotencyKey, TransactionId> transactionsByKey =
            new ConcurrentHashMap<>();

    @Override
    public void append(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        appendAll(List.of(entry));
    }

    /**
     * Validates the batch and claims its idempotency key before adding anything, so a rejected
     * batch records nothing. Once those steps pass, adding to in-memory queues cannot fail.
     */
    @Override
    public void appendAll(List<LedgerEntry> entries) {
        List<LedgerEntry> batch = List.copyOf(entries);   // rejects a null list and null elements
        if (batch.isEmpty()) {
            return;
        }
        TransactionId transactionId = batch.get(0).transactionId();
        if (batch.stream().anyMatch(e -> !e.transactionId().equals(transactionId))) {
            throw new IllegalArgumentException("All entries in a batch must belong to one transaction");
        }
        claimKey(batch, transactionId);

        for (LedgerEntry entry : batch) {
            entriesByAccount
                    .computeIfAbsent(entry.accountId(), id -> new ConcurrentLinkedQueue<>())
                    .add(entry);
        }
    }

    @Override
    public List<LedgerEntry> entriesFor(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Queue<LedgerEntry> entries = entriesByAccount.get(accountId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public Optional<TransactionId> findTransaction(IdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(transactionsByKey.get(key));
    }

    /**
     * Records the batch's idempotency key, if it has one. {@code putIfAbsent} is atomic, so when
     * two threads append the same key at the same moment exactly one succeeds.
     */
    private void claimKey(List<LedgerEntry> batch, TransactionId transactionId) {
        List<IdempotencyKey> keys = batch.stream()
                .flatMap(e -> e.idempotencyKey().stream())
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        if (keys.size() > 1) {
            throw new IllegalArgumentException("A batch can carry only one idempotency key: " + keys);
        }
        IdempotencyKey key = keys.get(0);
        TransactionId existing = transactionsByKey.putIfAbsent(key, transactionId);
        if (existing != null) {
            throw new DuplicateRequestException(key, existing);
        }
    }
}
