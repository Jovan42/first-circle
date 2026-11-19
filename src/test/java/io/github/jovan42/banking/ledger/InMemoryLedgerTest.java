package io.github.jovan42.banking.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.IdempotencyKey;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.DuplicateRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryLedgerTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final Ledger ledger = new InMemoryLedger();

    private static LedgerEntry deposit(AccountId accountId, String amount, String balanceAfter) {
        return LedgerEntry.deposit(
                TransactionId.newId(), accountId, Money.of(amount, EUR), Money.of(balanceAfter, EUR), NOW);
    }

    @Test
    void returnsEmptyHistoryForUnknownAccount() {
        assertThat(ledger.entriesFor(AccountId.newId())).isEmpty();
    }

    @Test
    void returnsEntriesInOrderAdded() {
        AccountId account = AccountId.newId();
        LedgerEntry first = deposit(account, "10", "10");
        LedgerEntry second = deposit(account, "5", "15");
        LedgerEntry third = deposit(account, "1", "16");

        ledger.append(first);
        ledger.append(second);
        ledger.append(third);

        assertThat(ledger.entriesFor(account)).containsExactly(first, second, third);
    }

    @Test
    void keepsAccountsSeparate() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        LedgerEntry forA = deposit(a, "10", "10");
        LedgerEntry forB = deposit(b, "20", "20");

        ledger.append(forA);
        ledger.append(forB);

        assertThat(ledger.entriesFor(a)).containsExactly(forA);
        assertThat(ledger.entriesFor(b)).containsExactly(forB);
    }

    @Test
    void historyCannotBeModifiedByCaller() {
        AccountId account = AccountId.newId();
        ledger.append(deposit(account, "10", "10"));

        List<LedgerEntry> history = ledger.entriesFor(account);

        assertThatThrownBy(history::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ledger.entriesFor(account)).hasSize(1);
    }

    @Test
    void historyIsASnapshot() {
        AccountId account = AccountId.newId();
        ledger.append(deposit(account, "10", "10"));
        List<LedgerEntry> before = ledger.entriesFor(account);

        ledger.append(deposit(account, "5", "15"));

        assertThat(before).hasSize(1);
        assertThat(ledger.entriesFor(account)).hasSize(2);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> ledger.append(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ledger.appendAll(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ledger.entriesFor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ledger.findTransaction(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void appendAllRecordsBothSidesOfATransfer() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        TransactionId tx = TransactionId.newId();
        LedgerEntry out = LedgerEntry.transferOut(tx, a, b, Money.of("30", EUR), Money.of("70", EUR), NOW);
        LedgerEntry in = LedgerEntry.transferIn(tx, b, a, Money.of("30", EUR), Money.of("80", EUR), NOW);

        ledger.appendAll(List.of(out, in));

        assertThat(ledger.entriesFor(a)).containsExactly(out);
        assertThat(ledger.entriesFor(b)).containsExactly(in);
    }

    @Test
    void appendAllRejectsEntriesFromDifferentTransactions() {
        AccountId a = AccountId.newId();

        assertThatThrownBy(() -> ledger.appendAll(List.of(deposit(a, "10", "10"), deposit(a, "5", "15"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.entriesFor(a)).isEmpty();
    }

    @Nested
    class IdempotencyKeys {

        private final IdempotencyKey key = IdempotencyKey.of("req-1");

        @Test
        void recordsKeyAndFindsItsTransaction() {
            LedgerEntry entry = deposit(AccountId.newId(), "10", "10").withIdempotencyKey(key);

            ledger.append(entry);

            assertThat(ledger.findTransaction(key)).contains(entry.transactionId());
            assertThat(ledger.findTransaction(IdempotencyKey.of("unknown"))).isEmpty();
        }

        @Test
        void rejectsSecondUseOfKeyAndRecordsNothing() {
            AccountId account = AccountId.newId();
            LedgerEntry original = deposit(account, "10", "10").withIdempotencyKey(key);
            ledger.append(original);

            LedgerEntry retry = deposit(account, "10", "20").withIdempotencyKey(key);

            assertThatThrownBy(() -> ledger.append(retry))
                    .isInstanceOfSatisfying(DuplicateRequestException.class, e -> {
                        assertThat(e.getKey()).isEqualTo(key);
                        assertThat(e.getOriginalTransactionId()).isEqualTo(original.transactionId());
                    });
            assertThat(ledger.entriesFor(account)).containsExactly(original);
        }

        @Test
        void transferBatchClaimsItsKeyOnce() {
            AccountId a = AccountId.newId();
            AccountId b = AccountId.newId();
            TransactionId tx = TransactionId.newId();

            ledger.appendAll(List.of(
                    LedgerEntry.transferOut(tx, a, b, Money.of("1", EUR), Money.of("9", EUR), NOW).withIdempotencyKey(key),
                    LedgerEntry.transferIn(tx, b, a, Money.of("1", EUR), Money.of("1", EUR), NOW).withIdempotencyKey(key)));

            assertThat(ledger.findTransaction(key)).contains(tx);
            assertThat(ledger.entriesFor(a)).hasSize(1);
            assertThat(ledger.entriesFor(b)).hasSize(1);
        }

        @Test
        void rejectsBatchWithTwoDifferentKeys() {
            AccountId a = AccountId.newId();
            AccountId b = AccountId.newId();
            TransactionId tx = TransactionId.newId();
            List<LedgerEntry> mixed = List.of(
                    LedgerEntry.transferOut(tx, a, b, Money.of("1", EUR), Money.of("9", EUR), NOW).withIdempotencyKey(key),
                    LedgerEntry.transferIn(tx, b, a, Money.of("1", EUR), Money.of("1", EUR), NOW)
                            .withIdempotencyKey(IdempotencyKey.of("other")));

            assertThatThrownBy(() -> ledger.appendAll(mixed)).isInstanceOf(IllegalArgumentException.class);
            assertThat(ledger.entriesFor(a)).isEmpty();
            assertThat(ledger.findTransaction(key)).isEmpty();
        }

        @Test
        void concurrentAppendsWithSameKeyLetExactlyOneWin() throws Exception {
            int threads = 50;
            int rounds = 20;
            AccountId account = AccountId.newId();

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int round = 0; round < rounds; round++) {
                    IdempotencyKey sameKey = IdempotencyKey.of("race-" + round);
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<Boolean>> results = new ArrayList<>();
                    for (int i = 0; i < threads; i++) {
                        results.add(pool.submit(() -> {
                            LedgerEntry entry = deposit(account, "1", "1").withIdempotencyKey(sameKey);
                            start.await();
                            try {
                                ledger.append(entry);
                                return true;
                            } catch (DuplicateRequestException e) {
                                return false;
                            }
                        }));
                    }
                    start.countDown();

                    int winners = 0;
                    for (Future<Boolean> result : results) {
                        if (result.get()) {
                            winners++;
                        }
                    }
                    assertThat(winners).as("round %d", round).isEqualTo(1);
                }
            }
            assertThat(ledger.entriesFor(account)).hasSize(rounds);
        }
    }

    @Test
    void appendAllWithANullEntryRecordsNothing() {
        AccountId a = AccountId.newId();
        List<LedgerEntry> withNull = new ArrayList<>();
        withNull.add(deposit(a, "10", "10"));
        withNull.add(null);

        assertThatThrownBy(() -> ledger.appendAll(withNull)).isInstanceOf(NullPointerException.class);
        assertThat(ledger.entriesFor(a)).isEmpty();
    }

    @Test
    void concurrentAppendsAreNotLost() throws Exception {
        int threads = 20;
        int entriesPerThread = 500;
        AccountId shared = AccountId.newId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < entriesPerThread; j++) {
                        ledger.append(deposit(shared, "1", "1"));
                    }
                    return null;
                }));
            }
            start.countDown();
        }

        // Rethrows any unexpected exception from a worker thread.
        for (Future<?> task : tasks) {
            task.get();
        }
        assertThat(ledger.entriesFor(shared)).hasSize(threads * entriesPerThread);
    }
}
