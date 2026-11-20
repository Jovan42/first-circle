package io.github.jovan42.banking.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.exception.InsufficientFundsException;
import io.github.jovan42.banking.ledger.EntryType;
import io.github.jovan42.banking.ledger.InMemoryLedger;
import io.github.jovan42.banking.ledger.LedgerEntry;
import io.github.jovan42.banking.repository.InMemoryAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Stress tests for the service under concurrent load. Each test checks invariants that must hold
 * for every possible interleaving, rather than a specific order of events:
 * <ul>
 *   <li>money is neither created nor destroyed by transfers,</li>
 *   <li>no balance ever goes negative,</li>
 *   <li>opposite transfers cannot deadlock,</li>
 *   <li>every account's ledger replays to exactly its balance, in order.</li>
 * </ul>
 */
class DefaultAccountServiceConcurrencyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Duration DEADLOCK_TIMEOUT = Duration.ofSeconds(10);

    private final AccountService service = new DefaultAccountService(
            new InMemoryAccountRepository(), new InMemoryLedger(), Clock.systemUTC());

    private static Money eur(String amount) {
        return Money.of(amount, EUR);
    }

    /** A random amount between 0.01 and 50.00. */
    private static Money randomAmount() {
        long cents = ThreadLocalRandom.current().nextLong(1, 5_001);
        return Money.of(BigDecimal.valueOf(cents, 2), EUR);
    }

    @Test
    void randomConcurrentTransfersConserveTotalMoney() throws Exception {
        List<AccountId> accounts = openAccounts(10, "1000.00");
        Money totalBefore = total(accounts);

        runConcurrently(16, 2_000, () -> {
            AccountId[] pair = randomPair(accounts);
            try {
                service.transfer(pair[0], pair[1], randomAmount());
            } catch (InsufficientFundsException expected) {
                // fine: the invariant is about money, not about every transfer succeeding
            }
        });

        assertThat(total(accounts)).isEqualTo(totalBefore);
        assertThat(accounts).allSatisfy(id ->
                assertThat(service.getBalance(id).amount()).isNotNegative());
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        AccountId a = service.openAccount(eur("1000000"));
        AccountId b = service.openAccount(eur("1000000"));

        // Half the transfers go A->B, the other half B->A, all at the same time. Without a fixed
        // lock order this deadlocks within milliseconds; runConcurrently's timeout turns the hang
        // into a test failure.
        runConcurrently(16, 5_000, () -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                service.transfer(a, b, eur("1.00"));
            } else {
                service.transfer(b, a, eur("1.00"));
            }
        });

        assertThat(service.getBalance(a).plus(service.getBalance(b))).isEqualTo(eur("2000000"));
    }

    @Test
    void circularTransfersAcrossManyAccountsDoNotDeadlock() throws Exception {
        // A->B, B->C, C->A ... form wait cycles if locks were taken in "from, then to" order.
        List<AccountId> accounts = openAccounts(3, "1000000");

        runConcurrently(16, 5_000, () -> {
            int from = ThreadLocalRandom.current().nextInt(accounts.size());
            int to = (from + 1) % accounts.size();
            service.transfer(accounts.get(from), accounts.get(to), eur("1.00"));
        });

        assertThat(total(accounts)).isEqualTo(eur("3000000"));
    }

    @Test
    void ledgerReplaysToExactBalanceAfterMixedConcurrentOperations() throws Exception {
        List<AccountId> accounts = openAccounts(5, "500.00");

        runConcurrently(16, 2_000, () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            AccountId account = accounts.get(random.nextInt(accounts.size()));
            try {
                switch (random.nextInt(3)) {
                    case 0 -> service.deposit(account, randomAmount());
                    case 1 -> service.withdraw(account, randomAmount());
                    default -> {
                        AccountId[] pair = randomPair(accounts);
                        service.transfer(pair[0], pair[1], randomAmount());
                    }
                }
            } catch (InsufficientFundsException expected) {
                // rejected operations must leave no trace; the replay below checks that
            }
        });

        for (AccountId account : accounts) {
            assertLedgerReplaysToBalance(account);
        }
        assertTransfersArePaired(accounts);
    }

    /**
     * Replays an account's history from zero. Each entry's {@code balanceAfter} must equal the
     * running total at that point (proving the ledger order matches the order the balance
     * changed), and the final total must equal the current balance.
     */
    private void assertLedgerReplaysToBalance(AccountId account) {
        Money running = Money.zero(EUR);
        for (LedgerEntry entry : service.getHistory(account)) {
            running = switch (entry.type()) {
                case DEPOSIT, TRANSFER_IN -> running.plus(entry.amount());
                case WITHDRAWAL, TRANSFER_OUT -> running.minus(entry.amount());
            };
            assertThat(entry.balanceAfter())
                    .as("balanceAfter of %s on %s", entry.transactionId(), account)
                    .isEqualTo(running);
        }
        assertThat(service.getBalance(account)).as("balance of %s", account).isEqualTo(running);
    }

    /** Every TRANSFER_OUT has exactly one matching TRANSFER_IN on the counterparty, and vice versa. */
    private void assertTransfersArePaired(List<AccountId> accounts) {
        Map<Object, List<LedgerEntry>> sidesByTransaction = new HashMap<>();
        for (AccountId account : accounts) {
            for (LedgerEntry entry : service.getHistory(account)) {
                if (entry.type() == EntryType.TRANSFER_IN || entry.type() == EntryType.TRANSFER_OUT) {
                    sidesByTransaction.computeIfAbsent(entry.transactionId(), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        assertThat(sidesByTransaction).isNotEmpty();
        sidesByTransaction.forEach((txId, sides) -> {
            assertThat(sides).as("sides of %s", txId).hasSize(2);
            LedgerEntry out = sides.stream().filter(e -> e.type() == EntryType.TRANSFER_OUT).findFirst().orElseThrow();
            LedgerEntry in = sides.stream().filter(e -> e.type() == EntryType.TRANSFER_IN).findFirst().orElseThrow();
            assertThat(out.amount()).isEqualTo(in.amount());
            assertThat(out.counterpartyAccountId()).contains(in.accountId());
            assertThat(in.counterpartyAccountId()).contains(out.accountId());
        });
    }

    private List<AccountId> openAccounts(int count, String balance) {
        List<AccountId> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accounts.add(service.openAccount(eur(balance)));
        }
        return accounts;
    }

    private Money total(List<AccountId> accounts) {
        return accounts.stream().map(service::getBalance).reduce(Money.zero(EUR), Money::plus);
    }

    private static AccountId[] randomPair(List<AccountId> accounts) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int from = random.nextInt(accounts.size());
        int to = random.nextInt(accounts.size() - 1);
        if (to >= from) {
            to++;   // pick a different account
        }
        return new AccountId[] {accounts.get(from), accounts.get(to)};
    }

    /**
     * Runs {@code operation} {@code iterationsPerThread} times on each of {@code threads} threads,
     * all released at once by a start gate.
     *
     * <ul>
     *   <li>Rethrows the first unexpected exception from a worker thread.</li>
     *   <li>Fails if the work does not finish within {@link #DEADLOCK_TIMEOUT}, so a deadlock
     *       becomes a test failure instead of a build that hangs forever.</li>
     *   <li>Worker threads are daemons, so deadlocked threads left behind cannot stop the JVM
     *       from exiting.</li>
     * </ul>
     */
    private static void runConcurrently(int threads, int iterationsPerThread, Runnable operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        operation.run();
                    }
                    return null;
                }));
            }
            start.countDown();

            long deadline = System.nanoTime() + DEADLOCK_TIMEOUT.toNanos();
            for (Future<?> task : tasks) {
                long remaining = deadline - System.nanoTime();
                try {
                    task.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError("Workers did not finish within " + DEADLOCK_TIMEOUT
                            + ": likely deadlock", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
