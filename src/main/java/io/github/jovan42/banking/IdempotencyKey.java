package io.github.jovan42.banking;

import java.util.Objects;

/**
 * Client-supplied key that identifies one logical request.
 *
 * <p>Retrying a request with the same key returns the original result instead of
 * applying the operation again.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
