package com.market.order.infrastructure.messaging;

import com.market.order.application.messaging.MessageContract;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

record OutboxMessage(
        UUID messageId,
        UUID aggregateId,
        String aggregateType,
        MessageContract contract,
        String source,
        String destinationTopic,
        String partitionKey,
        UUID correlationId,
        UUID causationId,
        Map<String, String> headers,
        String payload,
        Instant occurredAt
) {

    OutboxMessage {
        Objects.requireNonNull(messageId, "Message id must not be null");
        Objects.requireNonNull(aggregateId, "Aggregate id must not be null");
        Objects.requireNonNull(contract, "Message contract must not be null");
        Objects.requireNonNull(correlationId, "Correlation id must not be null");
        Objects.requireNonNull(headers, "Message headers must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence date must not be null");

        requireText(aggregateType, "Aggregate type");
        requireText(source, "Message source");
        requireText(destinationTopic, "Destination topic");
        requireText(partitionKey, "Partition key");
        requireText(payload, "Message payload");

        var safeHeaders = new LinkedHashMap<String, String>();

        for (var entry : headers.entrySet()) {
            requireText(entry.getKey(), "Header name");
            Objects.requireNonNull(entry.getValue(), "Header value must not be null");
            safeHeaders.put(entry.getKey(), entry.getValue());
        }

        headers = Collections.unmodifiableMap(safeHeaders);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
