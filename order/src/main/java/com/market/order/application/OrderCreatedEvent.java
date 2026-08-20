package com.market.order.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        List<Item> items,
        Instant occurredAt
) {

    public OrderCreatedEvent {
        items = List.copyOf(items);
    }

    public record Item(UUID productId, int quantity) {
    }
}
