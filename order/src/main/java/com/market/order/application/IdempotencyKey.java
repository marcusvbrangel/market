package com.market.order.application;

import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

    private static final int MAXIMUM_LENGTH = 100;
    private static final Pattern SUPPORTED_CHARACTERS = Pattern.compile("[A-Za-z0-9._:-]+");

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not be blank");
        }
        if (value.length() > MAXIMUM_LENGTH) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not exceed 100 characters");
        }
        if (!SUPPORTED_CHARACTERS.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key may contain only letters, numbers, dot, underscore, colon and hyphen"
            );
        }
    }

    public static IdempotencyKey from(String value) {
        return new IdempotencyKey(value);
    }
}
