package io.github.jovan42.banking.repository;

import io.github.jovan42.banking.domain.Account;
import io.github.jovan42.banking.domain.AccountId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link AccountRepository} backed by a {@link ConcurrentHashMap}.
 */
public final class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<AccountId, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public void add(Account account) {
        Objects.requireNonNull(account, "account");
        // Atomic insert: two threads adding the same id cannot both succeed.
        Account existing = accounts.putIfAbsent(account.getId(), account);
        if (existing != null) {
            throw new IllegalStateException("Account already exists: " + account.getId());
        }
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(accounts.get(id));
    }
}
