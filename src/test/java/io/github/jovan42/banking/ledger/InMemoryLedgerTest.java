package io.github.jovan42.banking.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    }

    @Test
    void appendAllRecordsEveryEntryInOrder() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        LedgerEntry first = deposit(a, "10", "10");
        LedgerEntry second = deposit(b, "20", "20");
        LedgerEntry third = deposit(a, "5", "15");

        ledger.appendAll(List.of(first, second, third));

        assertThat(ledger.entriesFor(a)).containsExactly(first, third);
        assertThat(ledger.entriesFor(b)).containsExactly(second);
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
