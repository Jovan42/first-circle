package io.github.jovan42.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.AccountNotFoundException;
import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InsufficientFundsException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import io.github.jovan42.banking.exception.SameAccountTransferException;
import io.github.jovan42.banking.ledger.EntryType;
import io.github.jovan42.banking.ledger.InMemoryLedger;
import io.github.jovan42.banking.ledger.Ledger;
import io.github.jovan42.banking.ledger.LedgerEntry;
import io.github.jovan42.banking.repository.InMemoryAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DefaultAccountServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final AccountService service = new DefaultAccountService(
            new InMemoryAccountRepository(), new InMemoryLedger(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static Money eur(String amount) {
        return Money.of(amount, EUR);
    }

    @Test
    void rejectsNullDependencies() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new DefaultAccountService(null, new InMemoryLedger(), clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultAccountService(new InMemoryAccountRepository(), null, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultAccountService(new InMemoryAccountRepository(), new InMemoryLedger(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    class OpenAccount {

        @Test
        void startsWithInitialDepositAndRecordsIt() {
            AccountId id = service.openAccount(eur("100"));

            assertThat(service.getBalance(id)).isEqualTo(eur("100"));
            assertThat(service.getHistory(id)).singleElement().satisfies(entry -> {
                assertThat(entry.type()).isEqualTo(EntryType.DEPOSIT);
                assertThat(entry.amount()).isEqualTo(eur("100"));
                assertThat(entry.balanceAfter()).isEqualTo(eur("100"));
                assertThat(entry.timestamp()).isEqualTo(NOW);
            });
        }

        @Test
        void zeroInitialDepositOpensEmptyAccountWithNoHistory() {
            AccountId id = service.openAccount(eur("0"));

            assertThat(service.getBalance(id).isZero()).isTrue();
            assertThat(service.getHistory(id)).isEmpty();
        }

        @Test
        void eachAccountGetsUniqueId() {
            assertThat(service.openAccount(eur("1"))).isNotEqualTo(service.openAccount(eur("1")));
        }

        @Test
        void rejectsNullInitialDeposit() {
            assertThatThrownBy(() -> service.openAccount(null)).isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    class Deposit {

        @Test
        void increasesBalanceAndRecordsEntry() {
            AccountId id = service.openAccount(eur("100"));

            TransactionId txId = service.deposit(id, eur("25.50"));

            assertThat(service.getBalance(id)).isEqualTo(eur("125.50"));
            LedgerEntry entry = last(service.getHistory(id));
            assertThat(entry.transactionId()).isEqualTo(txId);
            assertThat(entry.type()).isEqualTo(EntryType.DEPOSIT);
            assertThat(entry.amount()).isEqualTo(eur("25.50"));
            assertThat(entry.balanceAfter()).isEqualTo(eur("125.50"));
            assertThat(entry.counterpartyAccountId()).isEmpty();
            assertThat(entry.timestamp()).isEqualTo(NOW);
        }

        @Test
        void rejectedDepositChangesNothing() {
            AccountId id = service.openAccount(eur("100"));

            assertThatThrownBy(() -> service.deposit(id, eur("0"))).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> service.deposit(id, null)).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> service.deposit(id, Money.of("10", USD)))
                    .isInstanceOf(CurrencyMismatchException.class);

            assertThat(service.getBalance(id)).isEqualTo(eur("100"));
            assertThat(service.getHistory(id)).hasSize(1);
        }

        @Test
        void rejectsUnknownAccount() {
            AccountId unknown = AccountId.newId();
            assertThatThrownBy(() -> service.deposit(unknown, eur("10")))
                    .isInstanceOfSatisfying(AccountNotFoundException.class,
                            e -> assertThat(e.getAccountId()).isEqualTo(unknown));
        }
    }

    @Nested
    class Withdraw {

        @Test
        void decreasesBalanceAndRecordsEntry() {
            AccountId id = service.openAccount(eur("100"));

            TransactionId txId = service.withdraw(id, eur("30"));

            assertThat(service.getBalance(id)).isEqualTo(eur("70"));
            LedgerEntry entry = last(service.getHistory(id));
            assertThat(entry.transactionId()).isEqualTo(txId);
            assertThat(entry.type()).isEqualTo(EntryType.WITHDRAWAL);
            assertThat(entry.amount()).isEqualTo(eur("30"));
            assertThat(entry.balanceAfter()).isEqualTo(eur("70"));
        }

        @Test
        void allowsWithdrawingExactBalance() {
            AccountId id = service.openAccount(eur("100"));
            service.withdraw(id, eur("100"));
            assertThat(service.getBalance(id).isZero()).isTrue();
        }

        @Test
        void overdraftChangesNothing() {
            AccountId id = service.openAccount(eur("100"));

            assertThatThrownBy(() -> service.withdraw(id, eur("100.01")))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(service.getBalance(id)).isEqualTo(eur("100"));
            assertThat(service.getHistory(id)).hasSize(1);
        }

        @Test
        void rejectsUnknownAccount() {
            assertThatThrownBy(() -> service.withdraw(AccountId.newId(), eur("10")))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class Transfer {

        @Test
        void movesMoneyBetweenAccounts() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("50"));

            service.transfer(a, b, eur("30"));

            assertThat(service.getBalance(a)).isEqualTo(eur("70"));
            assertThat(service.getBalance(b)).isEqualTo(eur("80"));
        }

        @Test
        void recordsBothSidesUnderOneTransaction() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("50"));

            TransactionId txId = service.transfer(a, b, eur("30"));

            LedgerEntry out = last(service.getHistory(a));
            LedgerEntry in = last(service.getHistory(b));

            assertThat(out.transactionId()).isEqualTo(txId);
            assertThat(out.type()).isEqualTo(EntryType.TRANSFER_OUT);
            assertThat(out.amount()).isEqualTo(eur("30"));
            assertThat(out.balanceAfter()).isEqualTo(eur("70"));
            assertThat(out.counterpartyAccountId()).contains(b);
            assertThat(out.timestamp()).isEqualTo(NOW);

            assertThat(in.transactionId()).isEqualTo(txId);
            assertThat(in.type()).isEqualTo(EntryType.TRANSFER_IN);
            assertThat(in.amount()).isEqualTo(eur("30"));
            assertThat(in.balanceAfter()).isEqualTo(eur("80"));
            assertThat(in.counterpartyAccountId()).contains(a);
            assertThat(in.timestamp()).isEqualTo(NOW);
        }

        @Test
        void worksInBothDirectionsRegardlessOfIdOrder() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("100"));

            service.transfer(a, b, eur("10"));
            service.transfer(b, a, eur("25"));

            assertThat(service.getBalance(a)).isEqualTo(eur("115"));
            assertThat(service.getBalance(b)).isEqualTo(eur("85"));
        }

        @Test
        void allowsTransferringExactBalance() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("0"));

            service.transfer(a, b, eur("100"));

            assertThat(service.getBalance(a).isZero()).isTrue();
            assertThat(service.getBalance(b)).isEqualTo(eur("100"));
        }

        @Test
        void insufficientFundsChangesNeitherAccount() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("50"));

            assertThatThrownBy(() -> service.transfer(a, b, eur("100.01")))
                    .isInstanceOf(InsufficientFundsException.class);

            assertUnchanged(a, eur("100"), b, eur("50"));
        }

        @Test
        void destinationInOtherCurrencyChangesNeitherAccount() {
            AccountId a = service.openAccount(eur("100"));
            AccountId usdAccount = service.openAccount(Money.of("50", USD));

            assertThatThrownBy(() -> service.transfer(a, usdAccount, eur("10")))
                    .isInstanceOf(CurrencyMismatchException.class);

            assertUnchanged(a, eur("100"), usdAccount, Money.of("50", USD));
        }

        @Test
        void sourceInOtherCurrencyChangesNeitherAccount() {
            AccountId usdAccount = service.openAccount(Money.of("100", USD));
            AccountId b = service.openAccount(eur("50"));

            assertThatThrownBy(() -> service.transfer(usdAccount, b, eur("10")))
                    .isInstanceOf(CurrencyMismatchException.class);

            assertUnchanged(usdAccount, Money.of("100", USD), b, eur("50"));
        }

        @Test
        void rejectsZeroAndNullAmount() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("50"));

            assertThatThrownBy(() -> service.transfer(a, b, eur("0"))).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> service.transfer(a, b, null)).isInstanceOf(InvalidAmountException.class);

            assertUnchanged(a, eur("100"), b, eur("50"));
        }

        @Test
        void rejectsTransferToSameAccount() {
            AccountId a = service.openAccount(eur("100"));

            assertThatThrownBy(() -> service.transfer(a, a, eur("10")))
                    .isInstanceOfSatisfying(SameAccountTransferException.class,
                            e -> assertThat(e.getAccountId()).isEqualTo(a));

            assertThat(service.getBalance(a)).isEqualTo(eur("100"));
            assertThat(service.getHistory(a)).hasSize(1);
        }

        @Test
        void rejectsUnknownSourceOrDestination() {
            AccountId a = service.openAccount(eur("100"));
            AccountId unknown = AccountId.newId();

            assertThatThrownBy(() -> service.transfer(unknown, a, eur("10")))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> service.transfer(a, unknown, eur("10")))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(service.getBalance(a)).isEqualTo(eur("100"));
            assertThat(service.getHistory(a)).hasSize(1);
        }

        @Test
        void rejectsNullAccountIds() {
            AccountId a = service.openAccount(eur("100"));
            assertThatThrownBy(() -> service.transfer(null, a, eur("1"))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> service.transfer(a, null, eur("1"))).isInstanceOf(NullPointerException.class);
        }

        private void assertUnchanged(AccountId a, Money balanceA, AccountId b, Money balanceB) {
            assertThat(service.getBalance(a)).isEqualTo(balanceA);
            assertThat(service.getBalance(b)).isEqualTo(balanceB);
            assertThat(service.getHistory(a)).hasSize(1);   // only the opening deposit
            assertThat(service.getHistory(b)).hasSize(1);
        }
    }

    @Nested
    class Queries {

        @Test
        void historyIsInOrderOfOperations() {
            AccountId a = service.openAccount(eur("100"));
            AccountId b = service.openAccount(eur("0"));

            service.deposit(a, eur("10"));
            service.withdraw(a, eur("20"));
            service.transfer(a, b, eur("30"));

            assertThat(service.getHistory(a))
                    .extracting(LedgerEntry::type, LedgerEntry::balanceAfter)
                    .containsExactly(
                            tuple(EntryType.DEPOSIT, eur("100")),
                            tuple(EntryType.DEPOSIT, eur("110")),
                            tuple(EntryType.WITHDRAWAL, eur("90")),
                            tuple(EntryType.TRANSFER_OUT, eur("60")));
        }

        @Test
        void balanceAndHistoryRejectUnknownAccount() {
            AccountId unknown = AccountId.newId();
            assertThatThrownBy(() -> service.getBalance(unknown)).isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> service.getHistory(unknown)).isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void timestampsComeFromInjectedClock() {
            Instant later = Instant.parse("2030-06-15T08:30:00Z");
            AccountService atLater = new DefaultAccountService(
                    new InMemoryAccountRepository(), new InMemoryLedger(), Clock.fixed(later, ZoneOffset.UTC));

            AccountId id = atLater.openAccount(eur("1"));
            atLater.deposit(id, eur("1"));

            assertThat(atLater.getHistory(id)).allSatisfy(e -> assertThat(e.timestamp()).isEqualTo(later));
        }
    }

    /**
     * Injects failures into the ledger and the clock to prove that a failure at any point
     * leaves balances and history unchanged (there is no rollback, so ordering must guarantee it).
     */
    @Nested
    class FailureAtomicity {

        private final FailingLedger failingLedger = new FailingLedger();
        private final FailingClock failingClock = new FailingClock();
        private final AccountService fragile =
                new DefaultAccountService(new InMemoryAccountRepository(), failingLedger, failingClock);

        @Test
        void ledgerFailureDuringTransferChangesNothing() {
            AccountId a = fragile.openAccount(eur("100"));
            AccountId b = fragile.openAccount(eur("50"));

            failingLedger.failWrites = true;
            assertThatThrownBy(() -> fragile.transfer(a, b, eur("30"))).isInstanceOf(SimulatedFailure.class);
            failingLedger.failWrites = false;

            assertState(a, eur("100"), 1);
            assertState(b, eur("50"), 1);
        }

        @Test
        void clockFailureDuringTransferChangesNothing() {
            AccountId a = fragile.openAccount(eur("100"));
            AccountId b = fragile.openAccount(eur("50"));

            failingClock.fail = true;
            assertThatThrownBy(() -> fragile.transfer(a, b, eur("30"))).isInstanceOf(SimulatedFailure.class);
            failingClock.fail = false;

            assertState(a, eur("100"), 1);
            assertState(b, eur("50"), 1);
        }

        @Test
        void ledgerFailureDuringDepositAndWithdrawalChangesNothing() {
            AccountId a = fragile.openAccount(eur("100"));

            failingLedger.failWrites = true;
            assertThatThrownBy(() -> fragile.deposit(a, eur("10"))).isInstanceOf(SimulatedFailure.class);
            assertThatThrownBy(() -> fragile.withdraw(a, eur("10"))).isInstanceOf(SimulatedFailure.class);
            failingLedger.failWrites = false;

            assertState(a, eur("100"), 1);
        }

        @Test
        void clockFailureDuringDepositAndWithdrawalChangesNothing() {
            AccountId a = fragile.openAccount(eur("100"));

            failingClock.fail = true;
            assertThatThrownBy(() -> fragile.deposit(a, eur("10"))).isInstanceOf(SimulatedFailure.class);
            assertThatThrownBy(() -> fragile.withdraw(a, eur("10"))).isInstanceOf(SimulatedFailure.class);
            failingClock.fail = false;

            assertState(a, eur("100"), 1);
        }

        @Test
        void serviceKeepsWorkingAfterAFailure() {
            AccountId a = fragile.openAccount(eur("100"));
            AccountId b = fragile.openAccount(eur("50"));

            failingLedger.failWrites = true;
            assertThatThrownBy(() -> fragile.transfer(a, b, eur("30"))).isInstanceOf(SimulatedFailure.class);
            failingLedger.failWrites = false;

            fragile.transfer(a, b, eur("30"));   // locks were released; nothing is stuck

            assertState(a, eur("70"), 2);
            assertState(b, eur("80"), 2);
        }

        private void assertState(AccountId id, Money balance, int historySize) {
            assertThat(fragile.getBalance(id)).isEqualTo(balance);
            assertThat(fragile.getHistory(id)).hasSize(historySize);
        }
    }

    private static final class SimulatedFailure extends RuntimeException {
        SimulatedFailure(String message) {
            super(message);
        }
    }

    /** In-memory ledger whose writes can be switched to fail. Reads always work. */
    private static final class FailingLedger implements Ledger {
        private final Ledger delegate = new InMemoryLedger();
        volatile boolean failWrites;

        @Override
        public void append(LedgerEntry entry) {
            if (failWrites) {
                throw new SimulatedFailure("ledger unavailable");
            }
            delegate.append(entry);
        }

        @Override
        public void appendAll(List<LedgerEntry> entries) {
            if (failWrites) {
                throw new SimulatedFailure("ledger unavailable");
            }
            delegate.appendAll(entries);
        }

        @Override
        public List<LedgerEntry> entriesFor(AccountId accountId) {
            return delegate.entriesFor(accountId);
        }
    }

    /** Fixed clock that can be switched to fail. */
    private static final class FailingClock extends Clock {
        volatile boolean fail;

        @Override
        public Instant instant() {
            if (fail) {
                throw new SimulatedFailure("clock unavailable");
            }
            return NOW;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static LedgerEntry last(List<LedgerEntry> entries) {
        return entries.get(entries.size() - 1);
    }
}
