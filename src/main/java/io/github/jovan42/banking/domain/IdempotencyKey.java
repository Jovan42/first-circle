package io.github.jovan42.banking.domain;

import java.util.Objects;

/**
 * Client-supplied key identifying one logical request.
 *
 * <p>Sending the same request again with the same key returns the original result instead of
 * applying the operation a second time. Clients typically use a UUID per request.
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
