package com.market.order.application;

import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    private static final String MESSAGE_TYPE = "OrderCreated";
    private static final int SCHEMA_VERSION = 1;
    private static final MessageContract CONTRACT = new MessageContract(
            MessageCategory.EVENT,
            MESSAGE_TYPE,
            SCHEMA_VERSION
    );

    public OrderCreatedEvent(
            UUID eventId,
            UUID orderId,
            UUID customerId,
            List<Item> items,
            Instant occurredAt
    ) {
        this(eventId, MESSAGE_TYPE, SCHEMA_VERSION, orderId, orderId, customerId, items, occurredAt);
    }

    public OrderCreatedEvent {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(correlationId, "Correlation id must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(items, "Event items must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence date must not be null");

        if (!MESSAGE_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("OrderCreated event type must be OrderCreated");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("OrderCreated schema version must be 1");
        }
        if (!correlationId.equals(orderId)) {
            throw new IllegalArgumentException("OrderCreated correlation id must equal order id");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("OrderCreated must contain at least one item");
        }

        items = List.copyOf(items);
    }

    public MessageContract contract() {
        return CONTRACT;
    }

    public static MessageContract contractDefinition() {
        return CONTRACT;
    }

    public record Item(UUID productId, int quantity) {

        public Item {
            Objects.requireNonNull(productId, "Product id must not be null");

            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
        }
    }
}
