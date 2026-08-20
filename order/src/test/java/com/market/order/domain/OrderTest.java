package com.market.order.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void shouldCreateValidOrderAndProtectItemsFromMutation() {
        var item = validItem();
        var sourceItems = new java.util.ArrayList<>(List.of(item));

        var order = validOrder(sourceItems, new BigDecimal("20.00"));
        sourceItems.clear();

        assertThat(order.items()).containsExactly(item);
        assertThatThrownBy(() -> order.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCreatePendingOrderWithoutProductNameOrPrices() {
        var item = OrderItem.requested(UUID.randomUUID(), UUID.randomUUID(), 2);

        var order = Order.pending(
                UUID.randomUUID(),
                "ORD-20260819-TEST0001",
                UUID.randomUUID(),
                List.of(item),
                Instant.parse("2026-08-19T10:00:00Z")
        );

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isNull();
        assertThat(order.currency()).isNull();
        assertThat(order.items().getFirst()).satisfies(createdItem -> {
            assertThat(createdItem.productName()).isNull();
            assertThat(createdItem.unitPrice()).isNull();
            assertThat(createdItem.subtotal()).isNull();
        });
    }

    @Test
    void shouldRejectOrderWithoutItems() {
        assertThatThrownBy(() -> validOrder(List.of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order must contain at least one item");
    }

    @Test
    void shouldRejectOrderWhenTotalDoesNotMatchItems() {
        assertThatThrownBy(() -> validOrder(List.of(validItem()), new BigDecimal("19.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Total amount must equal the sum of item subtotals");
    }

    @Test
    void shouldRequireReasonForRejectedOrder() {
        assertThatThrownBy(() -> new Order(
                UUID.randomUUID(),
                "ORD-TEST-1",
                UUID.randomUUID(),
                OrderStatus.REJECTED,
                List.of(validItem()),
                new BigDecimal("20.00"),
                "BRL",
                null,
                Instant.parse("2026-08-19T10:00:00Z"),
                Instant.parse("2026-08-19T10:01:00Z")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rejected order must have a rejection reason");
    }

    @Test
    void shouldRejectInvalidItemSubtotal() {
        assertThatThrownBy(() -> new OrderItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Electronic product",
                2,
                new BigDecimal("10.00"),
                new BigDecimal("19.99")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Subtotal must equal unit price multiplied by quantity");
    }

    private Order validOrder(List<OrderItem> items, BigDecimal totalAmount) {
        return new Order(
                UUID.randomUUID(),
                "ORD-TEST-1",
                UUID.randomUUID(),
                OrderStatus.PENDING,
                items,
                totalAmount,
                "BRL",
                null,
                Instant.parse("2026-08-19T10:00:00Z"),
                Instant.parse("2026-08-19T10:01:00Z")
        );
    }

    private OrderItem validItem() {
        return new OrderItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Electronic product",
                2,
                new BigDecimal("10.00"),
                new BigDecimal("20.00")
        );
    }
}
