package io.github.jovan42.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InsufficientFundsException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private static Money eur(String amount) {
        return Money.of(amount, EUR);
    }

    private static Account openWith(String amount) {
        return Account.open(AccountId.newId(), eur(amount));
    }

    @Nested
    class Opening {

        @Test
        void startsWithInitialDeposit() {
            Account account = openWith("100.00");
            assertThat(account.getBalance()).isEqualTo(eur("100"));
            assertThat(account.getCurrency()).isEqualTo(EUR);
        }

        @Test
        void allowsZeroInitialDeposit() {
            assertThat(openWith("0").getBalance().isZero()).isTrue();
        }

        @Test
        void requiresIdAndInitialDeposit() {
            assertThatThrownBy(() -> Account.open(null, eur("1"))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Account.open(AccountId.newId(), null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Deposit {

        @Test
        void increasesBalance() {
            Account account = openWith("100");
            assertThat(account.deposit(eur("25.50"))).isEqualTo(eur("125.50"));
            assertThat(account.getBalance()).isEqualTo(eur("125.50"));
        }

        @Test
        void rejectsZero() {
            Account account = openWith("100");
            assertThatThrownBy(() -> account.deposit(eur("0"))).isInstanceOf(InvalidAmountException.class);
            assertThat(account.getBalance()).isEqualTo(eur("100"));
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> openWith("100").deposit(null)).isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsOtherCurrency() {
            Account account = openWith("100");
            assertThatThrownBy(() -> account.deposit(Money.of("10", USD)))
                    .isInstanceOf(CurrencyMismatchException.class);
            assertThat(account.getBalance()).isEqualTo(eur("100"));
        }
    }

    @Nested
    class Withdrawal {

        @Test
        void decreasesBalance() {
            Account account = openWith("100");
            assertThat(account.withdraw(eur("30.25"))).isEqualTo(eur("69.75"));
            assertThat(account.getBalance()).isEqualTo(eur("69.75"));
        }

        @Test
        void allowsWithdrawingExactBalance() {
            Account account = openWith("100");
            assertThat(account.withdraw(eur("100")).isZero()).isTrue();
        }

        @Test
        void rejectsOverdraftAndLeavesBalanceUnchanged() {
            Account account = openWith("100");
            assertThatThrownBy(() -> account.withdraw(eur("100.01")))
                    .isInstanceOfSatisfying(InsufficientFundsException.class, e -> {
                        assertThat(e.getAccountId()).isEqualTo(account.getId());
                        assertThat(e.getBalance()).isEqualTo(eur("100"));
                        assertThat(e.getRequested()).isEqualTo(eur("100.01"));
                    });
            assertThat(account.getBalance()).isEqualTo(eur("100"));
        }

        @Test
        void rejectsWithdrawalFromEmptyAccount() {
            assertThatThrownBy(() -> openWith("0").withdraw(eur("0.01")))
                    .isInstanceOf(InsufficientFundsException.class);
        }

        @Test
        void rejectsZero() {
            assertThatThrownBy(() -> openWith("100").withdraw(eur("0"))).isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> openWith("100").withdraw(null)).isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsOtherCurrency() {
            Account account = openWith("100");
            assertThatThrownBy(() -> account.withdraw(Money.of("10", USD)))
                    .isInstanceOf(CurrencyMismatchException.class);
            assertThat(account.getBalance()).isEqualTo(eur("100"));
        }

        @Test
        void releasesLockWhenWithdrawalFails() {
            Account account = openWith("10");
            assertThatThrownBy(() -> account.withdraw(eur("20"))).isInstanceOf(InsufficientFundsException.class);
            assertThat(account.getLock().isLocked()).isFalse();
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentWithdrawalsNeverOverdraw() throws Exception {
            int threads = 50;
            Account account = openWith("10.00");          // enough for exactly 10 withdrawals of 1.00
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Boolean>> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        start.await();                     // release all threads at once
                        try {
                            account.withdraw(eur("1.00"));
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    }));
                }
                start.countDown();
            }

            long succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(10);
            assertThat(account.getBalance().isZero()).isTrue();
        }

        @Test
        void concurrentDepositsAreNotLost() throws Exception {
            int threads = 20;
            int depositsPerThread = 500;
            Account account = openWith("0");
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        start.await();
                        for (int j = 0; j < depositsPerThread; j++) {
                            account.deposit(eur("0.01"));
                        }
                        return null;
                    });
                }
                start.countDown();
            }

            // 20 * 500 * 0.01 = 100.00
            assertThat(account.getBalance()).isEqualTo(eur("100.00"));
        }

        @Test
        void mixedDepositsAndWithdrawalsBalanceOut() throws Exception {
            int threads = 20;
            int opsPerThread = 500;
            Account account = openWith("5.00");
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Integer>> withdrawalCounts = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    boolean depositor = i % 2 == 0;
                    withdrawalCounts.add(pool.submit(() -> {
                        start.await();
                        int withdrawn = 0;
                        for (int j = 0; j < opsPerThread; j++) {
                            if (depositor) {
                                account.deposit(eur("1.00"));
                            } else {
                                try {
                                    account.withdraw(eur("1.00"));
                                    withdrawn++;
                                } catch (InsufficientFundsException e) {
                                    // expected when withdrawals outrun deposits
                                }
                            }
                        }
                        return withdrawn;
                    }));
                }
                start.countDown();
            }

            int totalWithdrawn = 0;
            for (Future<Integer> count : withdrawalCounts) {
                totalWithdrawn += count.get();
            }
            int totalDeposited = (threads / 2) * opsPerThread;

            // final balance = initial + deposits - successful withdrawals, to the cent
            Money expected = eur(String.valueOf(5 + totalDeposited - totalWithdrawn));
            assertThat(account.getBalance()).isEqualTo(expected);
        }
    }
}
