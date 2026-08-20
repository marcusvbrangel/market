package com.market.order.application.messaging;

import java.util.Objects;

public record MessageContract(
        MessageCategory category,
        String messageType,
        int schemaVersion
) {

    public MessageContract {
        Objects.requireNonNull(category, "Message category must not be null");

        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("Message type must not be blank");
        }
        if (messageType.length() > 100) {
            throw new IllegalArgumentException("Message type must not exceed 100 characters");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("Schema version must be positive");
        }
    }
}
