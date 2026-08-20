package com.market.order.interfaces.rest;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @Schema(
                description = "Identificador do cliente",
                example = "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a"
        )
        @NotNull UUID customerId,
        @ArraySchema(arraySchema = @Schema(description = "Itens solicitados"), minItems = 1)
        @NotEmpty List<@Valid Item> items
) {

    public CreateOrderRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(
            @Schema(
                    description = "Identificador do produto",
                    example = "6c20b55a-2e09-4473-98a6-411f48a8bb23"
            )
            @NotNull UUID productId,
            @Schema(description = "Quantidade solicitada", example = "2", minimum = "1")
            @Positive int quantity
    ) {
    }
}
