package io.github.jovan42.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        assertThatThrownBy(() -> ledger.entriesFor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentAppendsAreNotLost() throws Exception {
        int threads = 20;
        int entriesPerThread = 500;
        AccountId shared = AccountId.newId();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < entriesPerThread; j++) {
                        ledger.append(deposit(shared, "1", "1"));
                    }
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(ledger.entriesFor(shared)).hasSize(threads * entriesPerThread);
    }
}
