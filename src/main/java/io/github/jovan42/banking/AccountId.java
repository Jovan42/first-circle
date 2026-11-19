package io.github.jovan42.banking;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier of an account.
 *
 * <p>Comparable so that transfers can lock two accounts in a consistent order
 * and avoid deadlocks.
 */
public record AccountId(UUID value) implements Comparable<AccountId> {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId of(String value) {
        return new AccountId(UUID.fromString(value));
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
