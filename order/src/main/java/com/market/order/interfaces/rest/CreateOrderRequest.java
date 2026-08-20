package com.market.order.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotEmpty List<@Valid Item> items
) {

    public CreateOrderRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(
            @NotNull UUID productId,
            @Positive int quantity
    ) {
    }
}
