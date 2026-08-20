package com.market.order.interfaces.rest;

import com.market.order.domain.Order;

import java.time.Instant;
import java.util.UUID;

public record CreateOrderResponse(
        UUID id,
        String orderNumber,
        OrderResponse.Status status,
        Instant createdAt
) {

    public static CreateOrderResponse from(Order order) {
        return new CreateOrderResponse(
                order.id(),
                order.orderNumber(),
                OrderResponse.Status.valueOf(order.status().name()),
                order.createdAt()
        );
    }
}
