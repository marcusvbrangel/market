package com.market.order.interfaces.rest;

import com.market.order.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record CreateOrderResponse(
        @Schema(description = "Identificador do pedido", example = "e309bd65-d3e7-486f-b115-42e5d8ec5f08")
        UUID id,
        @Schema(description = "Número legível do pedido", example = "ORD-20260819-E309BD65")
        String orderNumber,
        @Schema(description = "Estado inicial do pedido", example = "PENDING")
        OrderResponse.Status status,
        @Schema(description = "Instante de criação em UTC", example = "2026-08-19T23:20:16.165436398Z")
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
