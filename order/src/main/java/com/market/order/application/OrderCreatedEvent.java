package com.market.order.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID correlationId,
        UUID orderId,
        UUID customerId,
        List<Item> items,
        Instant occurredAt
) {

    public OrderCreatedEvent(
            UUID eventId,
            UUID orderId,
            UUID customerId,
            List<Item> items,
            Instant occurredAt
    ) {
        this(eventId, "OrderCreated", 1, orderId, orderId, customerId, items, occurredAt);
    }

    public OrderCreatedEvent {
        items = List.copyOf(items);
    }

    public record Item(UUID productId, int quantity) {
    }
}
