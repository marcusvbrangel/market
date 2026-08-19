package com.market.order.interfaces.rest;

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
        @NotNull @DecimalMin("0.00") BigDecimal totalAmount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String rejectionReason,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {

    public OrderResponse {
        items = items == null ? null : List.copyOf(items);
    }

    public enum Status {
        PENDING,
        CONFIRMED,
        REJECTED
    }

    public record Item(
            @NotNull UUID id,
            @NotNull UUID productId,
            @NotBlank @Size(max = 200) String productName,
            @Positive int quantity,
            @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
            @NotNull @DecimalMin("0.00") BigDecimal subtotal
    ) {
    }
}
