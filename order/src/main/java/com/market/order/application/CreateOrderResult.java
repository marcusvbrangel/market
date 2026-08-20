package com.market.order.application;

import com.market.order.domain.Order;
import com.market.order.domain.OrderStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CreateOrderResult(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        Instant createdAt,
        boolean replayed
) {

    public CreateOrderResult {
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(status, "Order status must not be null");
        Objects.requireNonNull(createdAt, "Creation date must not be null");

        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("Order number must not be blank");
        }
    }

    public static CreateOrderResult created(Order order) {
        return from(order, false);
    }

    private static CreateOrderResult from(Order order, boolean replayed) {
        Objects.requireNonNull(order, "Order must not be null");

        return new CreateOrderResult(
                order.id(),
                order.orderNumber(),
                order.status(),
                order.createdAt(),
                replayed
        );
    }
}
