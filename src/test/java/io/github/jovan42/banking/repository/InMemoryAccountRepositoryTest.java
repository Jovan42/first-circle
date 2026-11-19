package io.github.jovan42.banking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.domain.Account;
import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryAccountRepositoryTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final AccountRepository repository = new InMemoryAccountRepository();

    private static Account newAccount(AccountId id, String balance) {
        return Account.open(id, Money.of(balance, EUR));
    }

    @Test
    void findsAddedAccount() {
        Account account = newAccount(AccountId.newId(), "100");
        repository.add(account);

        assertThat(repository.findById(account.getId())).containsSame(account);
    }

    @Test
    void returnsEmptyForUnknownAccount() {
        assertThat(repository.findById(AccountId.newId())).isEmpty();
    }

    @Test
    void returnsSameInstanceSoChangesAreVisible() {
        Account account = newAccount(AccountId.newId(), "100");
        repository.add(account);

        repository.findById(account.getId()).orElseThrow().deposit(Money.of("5", EUR));

        assertThat(repository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualTo(Money.of("105", EUR));
    }

    @Test
    void rejectsDuplicateIdAndKeepsOriginal() {
        AccountId id = AccountId.newId();
        Account original = newAccount(id, "100");
        repository.add(original);

        assertThatThrownBy(() -> repository.add(newAccount(id, "999")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id.toString());
        assertThat(repository.findById(id)).containsSame(original);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> repository.add(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentAddsOfDifferentAccountsAreAllStored() throws Exception {
        int threads = 20;
        int accountsPerThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<AccountId>>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    List<AccountId> ids = new ArrayList<>();
                    for (int j = 0; j < accountsPerThread; j++) {
                        Account account = newAccount(AccountId.newId(), "1");
                        repository.add(account);
                        ids.add(account.getId());
                    }
                    return ids;
                }));
            }
            start.countDown();
        }

        for (Future<List<AccountId>> result : results) {
            for (AccountId id : result.get()) {
                assertThat(repository.findById(id)).isPresent();
            }
        }
    }

    @Test
    void concurrentAddsOfSameIdLetExactlyOneWin() throws Exception {
        int threads = 20;
        AccountId id = AccountId.newId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Account>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    Account candidate = newAccount(id, "1");
                    start.await();
                    try {
                        repository.add(candidate);
                        return candidate;
                    } catch (IllegalStateException e) {
                        return null;
                    }
                }));
            }
            start.countDown();
        }

        List<Account> winners = new ArrayList<>();
        for (Future<Account> result : results) {
            if (result.get() != null) {
                winners.add(result.get());
            }
        }
        assertThat(winners).hasSize(1);
        assertThat(repository.findById(id)).containsSame(winners.get(0));
    }
}
