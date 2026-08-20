package com.market.order.interfaces.rest;

import com.market.order.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "Identificador do pedido")
        @NotNull UUID id,
        @Schema(description = "Número legível do pedido", example = "ORD-2026-000001")
        @NotBlank @Size(max = 50) String orderNumber,
        @Schema(description = "Identificador do cliente")
        @NotNull UUID customerId,
        @Schema(description = "Estado atual do pedido")
        @NotNull Status status,
        @NotEmpty List<@Valid Item> items,
        @Schema(description = "Valor total; ausente enquanto o pedido ainda não foi precificado", example = "6549.40")
        @DecimalMin("0.00") BigDecimal totalAmount,
        @Schema(description = "Moeda ISO 4217; ausente enquanto o pedido ainda não foi precificado", example = "BRL")
        @Size(min = 3, max = 3) String currency,
        @Schema(description = "Motivo da rejeição, quando aplicável")
        @Size(max = 500) String rejectionReason,
        @Schema(description = "Instante de criação em UTC")
        @NotNull Instant createdAt,
        @Schema(description = "Instante da última atualização em UTC")
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
            @Schema(description = "Identificador do item")
            @NotNull UUID id,
            @Schema(description = "Identificador do produto")
            @NotNull UUID productId,
            @Schema(description = "Nome enriquecido do produto; pode estar ausente")
            @Size(max = 200) String productName,
            @Schema(description = "Quantidade solicitada", minimum = "1")
            @Positive int quantity,
            @Schema(description = "Preço unitário; pode estar ausente")
            @DecimalMin("0.00") BigDecimal unitPrice,
            @Schema(description = "Subtotal do item; pode estar ausente")
            @DecimalMin("0.00") BigDecimal subtotal
    ) {
    }
}
