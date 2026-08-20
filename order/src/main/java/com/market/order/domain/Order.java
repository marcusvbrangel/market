package com.market.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Order(
        UUID id,
        String orderNumber,
        UUID customerId,
        OrderStatus status,
        List<OrderItem> items,
        BigDecimal totalAmount,
        String currency,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static Order pending(
            UUID id,
            String orderNumber,
            UUID customerId,
            List<OrderItem> items,
            Instant createdAt
    ) {
        return new Order(
                id, orderNumber, customerId, OrderStatus.PENDING, items,
                null, null, null, createdAt, createdAt
        );
    }

    public Order {
        Objects.requireNonNull(id, "Order id must not be null");
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(status, "Order status must not be null");
        Objects.requireNonNull(items, "Order items must not be null");
        Objects.requireNonNull(createdAt, "Creation date must not be null");
        Objects.requireNonNull(updatedAt, "Update date must not be null");

        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("Order number must not be blank");
        }
        if (orderNumber.length() > 50) {
            throw new IllegalArgumentException("Order number must not exceed 50 characters");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        if (totalAmount != null && totalAmount.signum() < 0) {
            throw new IllegalArgumentException("Total amount must not be negative");
        }
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must use a three-letter uppercase code");
        }
        if (rejectionReason != null && rejectionReason.length() > 500) {
            throw new IllegalArgumentException("Rejection reason must not exceed 500 characters");
        }
        if (status == OrderStatus.REJECTED && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("Rejected order must have a rejection reason");
        }
        if (status != OrderStatus.REJECTED && rejectionReason != null) {
            throw new IllegalArgumentException("Only rejected order may have a rejection reason");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Update date must not precede creation date");
        }

        items = List.copyOf(items);
        var pricedItems = items.stream().filter(OrderItem::isPriced).count();
        if (pricedItems != 0 && pricedItems != items.size()) {
            throw new IllegalArgumentException("All order items must have the same pricing state");
        }
        if ((totalAmount == null) != (currency == null)) {
            throw new IllegalArgumentException("Total amount and currency must be provided together");
        }
        if (pricedItems == 0 && totalAmount != null) {
            throw new IllegalArgumentException("Unpriced order must not have a total amount");
        }
        if (pricedItems == items.size() && totalAmount == null) {
            throw new IllegalArgumentException("Priced order must have a total amount and currency");
        }
        if (status == OrderStatus.CONFIRMED && pricedItems != items.size()) {
            throw new IllegalArgumentException("Confirmed order must be priced");
        }

        var expectedTotal = items.stream()
                .filter(OrderItem::isPriced)
                .map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAmount != null && totalAmount.compareTo(expectedTotal) != 0) {
            throw new IllegalArgumentException("Total amount must equal the sum of item subtotals");
        }
    }
}
