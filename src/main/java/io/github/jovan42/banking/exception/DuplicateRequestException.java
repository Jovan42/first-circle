package io.github.jovan42.banking.exception;

import io.github.jovan42.banking.domain.IdempotencyKey;
import io.github.jovan42.banking.domain.TransactionId;

/**
 * Thrown when a request reuses an idempotency key that is already recorded in the ledger.
 * Nothing is changed. The original transaction id tells the client its first attempt succeeded.
 */
public final class DuplicateRequestException extends BankingException {

    private final IdempotencyKey key;
    private final TransactionId originalTransactionId;

    public DuplicateRequestException(IdempotencyKey key, TransactionId originalTransactionId) {
        super("Request with idempotency key " + key
                + " was already processed as transaction " + originalTransactionId);
        this.key = key;
        this.originalTransactionId = originalTransactionId;
    }

    public IdempotencyKey getKey() {
        return key;
    }

    public TransactionId getOriginalTransactionId() {
        return originalTransactionId;
    }
}
