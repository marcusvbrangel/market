package com.market.order.interfaces.rest;

import com.market.order.domain.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        @NotNull UUID id,
        @NotBlank @Size(max = 50) String orderNumber,
        @NotNull UUID customerId,
        @NotNull Status status,
        @NotEmpty List<@Valid Item> items,
        @DecimalMin("0.00") BigDecimal totalAmount,
        @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String rejectionReason,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {

    public OrderResponse {
        items = items == null ? null : List.copyOf(items);
    }

    public static OrderResponse from(Order order) {
        var responseItems = order.items().stream()
                .map(item -> new Item(
                        item.id(),
                        item.productId(),
                        item.productName(),
                        item.quantity(),
                        item.unitPrice(),
                        item.subtotal()
                ))
                .toList();

        return new OrderResponse(
                order.id(),
                order.orderNumber(),
                order.customerId(),
                Status.valueOf(order.status().name()),
                responseItems,
                order.totalAmount(),
                order.currency(),
                order.rejectionReason(),
                order.createdAt(),
                order.updatedAt()
        );
    }

    public enum Status {
        PENDING,
        CONFIRMED,
        REJECTED
    }

    public record Item(
            @NotNull UUID id,
            @NotNull UUID productId,
            @Size(max = 200) String productName,
            @Positive int quantity,
            @DecimalMin("0.00") BigDecimal unitPrice,
            @DecimalMin("0.00") BigDecimal subtotal
    ) {
    }
}
