package com.market.order.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record OrderCreationRequestFingerprint(short version, String hash) {

    private static final Pattern SHA_256_HEXADECIMAL = Pattern.compile("[0-9a-f]{64}");

    public OrderCreationRequestFingerprint {
        if (version <= 0) {
            throw new IllegalArgumentException("Fingerprint version must be positive");
        }

        Objects.requireNonNull(hash, "Fingerprint hash must not be null");

        if (!SHA_256_HEXADECIMAL.matcher(hash).matches()) {
            throw new IllegalArgumentException("Fingerprint hash must be a lowercase SHA-256 value");
        }
    }
}
