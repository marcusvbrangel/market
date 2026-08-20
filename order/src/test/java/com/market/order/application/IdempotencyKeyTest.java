package com.market.order.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void shouldAcceptMinimumAndMaximumSupportedLengths() {
        var minimumKey = IdempotencyKey.from("a");
        var maximumKey = IdempotencyKey.from("a".repeat(100));

        assertThat(minimumKey.value()).isEqualTo("a");
        assertThat(maximumKey.value()).hasSize(100);
    }

    @Test
    void shouldPreserveCaseBecauseKeyIsCaseSensitive() {
        var uppercaseKey = IdempotencyKey.from("Checkout-Key");
        var lowercaseKey = IdempotencyKey.from("checkout-key");

        assertThat(uppercaseKey).isNotEqualTo(lowercaseKey);
    }

    @Test
    void shouldRejectBlankAndOversizedKeys() {
        assertThatThrownBy(() -> IdempotencyKey.from(""))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKey.from("a".repeat(101)))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
