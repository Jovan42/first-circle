package io.github.jovan42.banking.exception;

import io.github.jovan42.banking.IdempotencyKey;

/**
 * Thrown when an idempotency key is reused for a request that differs from the original
 * (different operation, accounts or amount). This usually signals a client bug, so it is
 * rejected rather than silently returning the earlier result.
 */
public final class IdempotencyConflictException extends BankingException {

    private final IdempotencyKey key;

    public IdempotencyConflictException(IdempotencyKey key) {
        super("Idempotency key " + key + " was already used for a different request");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
