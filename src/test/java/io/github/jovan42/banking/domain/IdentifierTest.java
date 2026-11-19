package io.github.jovan42.banking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentifierTest {

    @Test
    void generatedAccountIdsAreUnique() {
        assertThat(AccountId.newId()).isNotEqualTo(AccountId.newId());
    }

    @Test
    void accountIdRoundTripsThroughString() {
        AccountId id = AccountId.newId();
        assertThat(AccountId.of(id.toString())).isEqualTo(id);
    }

    @Test
    void accountIdsHaveConsistentOrdering() {
        AccountId a = AccountId.of("00000000-0000-0000-0000-000000000001");
        AccountId b = AccountId.of("00000000-0000-0000-0000-000000000002");
        assertThat(a).isLessThan(b);
        assertThat(b).isGreaterThan(a);
        assertThat(a.compareTo(AccountId.of(a.toString()))).isZero();
    }

    @Test
    void accountIdRejectsInvalidInput() {
        assertThatThrownBy(() -> AccountId.of("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void generatedTransactionIdsAreUnique() {
        assertThat(TransactionId.newId()).isNotEqualTo(TransactionId.newId());
    }

    @Test
    void transactionIdRoundTripsThroughString() {
        TransactionId id = TransactionId.newId();
        assertThat(TransactionId.of(id.toString())).isEqualTo(id);
    }
}
