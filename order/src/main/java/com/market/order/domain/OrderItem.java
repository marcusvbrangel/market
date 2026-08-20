package com.market.order.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public record OrderItem(
        UUID id,
        UUID productId,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {

    public static OrderItem requested(UUID id, UUID productId, int quantity) {
        return new OrderItem(id, productId, null, quantity, null, null);
    }

    public OrderItem {
        Objects.requireNonNull(id, "Item id must not be null");
        Objects.requireNonNull(productId, "Product id must not be null");
        if (productName != null && productName.isBlank()) {
            throw new IllegalArgumentException("Product name must not be blank when provided");
        }
        if (productName != null && productName.length() > 200) {
            throw new IllegalArgumentException("Product name must not exceed 200 characters");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        var pricingFields = Stream.of(productName, unitPrice, subtotal)
                .filter(Objects::nonNull)
                .count();
        if (pricingFields != 0 && pricingFields != 3) {
            throw new IllegalArgumentException("Product name, unit price and subtotal must be provided together");
        }
        if (unitPrice != null && unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must not be negative");
        }
        if (subtotal != null && subtotal.signum() < 0) {
            throw new IllegalArgumentException("Subtotal must not be negative");
        }

        var expectedSubtotal = unitPrice == null
                ? null
                : unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (subtotal != null && subtotal.compareTo(expectedSubtotal) != 0) {
            throw new IllegalArgumentException("Subtotal must equal unit price multiplied by quantity");
        }
    }

    public boolean isPriced() {
        return unitPrice != null;
    }
}
