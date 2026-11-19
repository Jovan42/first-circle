package io.github.jovan42.banking.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jovan42.banking.domain.AccountId;
import io.github.jovan42.banking.domain.Money;
import io.github.jovan42.banking.domain.TransactionId;
import io.github.jovan42.banking.exception.CurrencyMismatchException;
import io.github.jovan42.banking.exception.InvalidAmountException;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LedgerEntryTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final TransactionId tx = TransactionId.newId();
    private final AccountId a = AccountId.newId();
    private final AccountId b = AccountId.newId();

    private static Money eur(String amount) {
        return Money.of(amount, EUR);
    }

    @Nested
    class Factories {

        @Test
        void depositHoldsAllFieldsAndNoCounterparty() {
            LedgerEntry entry = LedgerEntry.deposit(tx, a, eur("50"), eur("150"), NOW);

            assertThat(entry.transactionId()).isEqualTo(tx);
            assertThat(entry.accountId()).isEqualTo(a);
            assertThat(entry.type()).isEqualTo(EntryType.DEPOSIT);
            assertThat(entry.amount()).isEqualTo(eur("50"));
            assertThat(entry.balanceAfter()).isEqualTo(eur("150"));
            assertThat(entry.counterpartyAccountId()).isEmpty();
            assertThat(entry.timestamp()).isEqualTo(NOW);
        }

        @Test
        void withdrawalHasNoCounterparty() {
            LedgerEntry entry = LedgerEntry.withdrawal(tx, a, eur("50"), eur("0"), NOW);

            assertThat(entry.type()).isEqualTo(EntryType.WITHDRAWAL);
            assertThat(entry.counterpartyAccountId()).isEmpty();
            assertThat(entry.balanceAfter().isZero()).isTrue();
        }

        @Test
        void transferSidesShareTransactionAndNameEachOther() {
            LedgerEntry out = LedgerEntry.transferOut(tx, a, b, eur("30"), eur("70"), NOW);
            LedgerEntry in = LedgerEntry.transferIn(tx, b, a, eur("30"), eur("80"), NOW);

            assertThat(out.type()).isEqualTo(EntryType.TRANSFER_OUT);
            assertThat(out.accountId()).isEqualTo(a);
            assertThat(out.counterpartyAccountId()).contains(b);

            assertThat(in.type()).isEqualTo(EntryType.TRANSFER_IN);
            assertThat(in.accountId()).isEqualTo(b);
            assertThat(in.counterpartyAccountId()).contains(a);

            assertThat(out.transactionId()).isEqualTo(in.transactionId());
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsNullFields() {
            Money m = eur("1");
            Optional<AccountId> none = Optional.empty();
            EntryType type = EntryType.DEPOSIT;

            assertThatThrownBy(() -> new LedgerEntry(null, a, type, m, m, none, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, null, type, m, m, none, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, a, null, m, m, none, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, a, type, null, m, none, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, a, type, m, null, none, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, a, type, m, m, null, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new LedgerEntry(tx, a, type, m, m, none, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> LedgerEntry.deposit(tx, a, eur("0"), eur("10"), NOW))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsAmountAndBalanceInDifferentCurrencies() {
            assertThatThrownBy(() -> LedgerEntry.deposit(tx, a, eur("10"), Money.of("10", USD), NOW))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        void transferRequiresCounterparty() {
            assertThatThrownBy(() -> new LedgerEntry(
                    tx, a, EntryType.TRANSFER_OUT, eur("1"), eur("1"), Optional.empty(), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a counterparty");
            assertThatThrownBy(() -> new LedgerEntry(
                    tx, a, EntryType.TRANSFER_IN, eur("1"), eur("1"), Optional.empty(), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void depositAndWithdrawalRejectCounterparty() {
            assertThatThrownBy(() -> new LedgerEntry(
                    tx, a, EntryType.DEPOSIT, eur("1"), eur("1"), Optional.of(b), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not have a counterparty");
            assertThatThrownBy(() -> new LedgerEntry(
                    tx, a, EntryType.WITHDRAWAL, eur("1"), eur("1"), Optional.of(b), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void counterpartyCannotBeOwnAccount() {
            assertThatThrownBy(() -> LedgerEntry.transferOut(tx, a, a, eur("1"), eur("1"), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must differ");
        }

        @Test
        void transferFactoryRejectsNullCounterparty() {
            assertThatThrownBy(() -> LedgerEntry.transferIn(tx, a, null, eur("1"), eur("1"), NOW))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
