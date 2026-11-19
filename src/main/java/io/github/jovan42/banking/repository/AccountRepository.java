package io.github.jovan42.banking.repository;

import io.github.jovan42.banking.domain.Account;
import io.github.jovan42.banking.domain.AccountId;
import java.util.Optional;

/**
 * Storage for accounts. Implementations must be thread-safe.
 *
 * <p>Accounts are mutable entities that guard their own balance, so there is no {@code save}
 * or {@code update}: once added, the stored instance is the one that changes. A database-backed
 * implementation would need an explicit update with optimistic locking instead.
 */
public interface AccountRepository {

    /**
     * Stores a new account.
     *
     * @throws IllegalStateException if an account with the same id already exists
     */
    void add(Account account);

    Optional<Account> findById(AccountId id);
}
