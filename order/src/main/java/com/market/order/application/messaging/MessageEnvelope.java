package com.market.order.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record MessageEnvelope<T>(
        UUID messageId,
        String messageType,
        int schemaVersion,
        Instant occurredAt,
        String source,
        UUID correlationId,
        UUID causationId,
        UUID orderId,
        T payload
) {

    private static final Pattern SOURCE_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,99}");

    public MessageEnvelope {
        Objects.requireNonNull(messageId, "Message id must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence date must not be null");
        Objects.requireNonNull(correlationId, "Correlation id must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(payload, "Payload must not be null");

        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("Message type must not be blank");
        }
        if (messageType.length() > 100) {
            throw new IllegalArgumentException("Message type must not exceed 100 characters");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("Schema version must be positive");
        }
        if (source == null || !SOURCE_PATTERN.matcher(source).matches()) {
            throw new IllegalArgumentException("Message source has an invalid format");
        }
    }
}
