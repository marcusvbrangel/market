package com.market.order.application;

import com.market.order.application.port.OrderCreationPort;
import com.market.order.domain.Order;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void shouldCreatePendingUnpricedOrderAndOutboxEvent() {
        var port = mock(OrderCreationPort.class);
        when(port.createOrReplay(any(), any(), any(), any()))
                .thenAnswer(invocation -> CreateOrderResult.created(invocation.getArgument(0)));

        var requestHasher = new OrderCreationRequestHasher();
        var service = new CreateOrderService(
                port,
                requestHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var result = service.create(
                "checkout-test-001",
                customerId,
                List.of(new CreateOrderService.ItemCommand(productId, 2))
        );

        var orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(port).createOrReplay(
                orderCaptor.capture(),
                any(OrderCreatedEvent.class),
                any(IdempotencyKey.class),
                any(OrderCreationRequestFingerprint.class)
        );

        var order = orderCaptor.getValue();

        assertThat(result.orderId()).isEqualTo(order.id());
        assertThat(result.replayed()).isFalse();
        assertThat(order.orderNumber()).startsWith("ORD-20260819-");
        assertThat(order.customerId()).isEqualTo(customerId);
        assertThat(order.status().name()).isEqualTo("PENDING");
        assertThat(order.totalAmount()).isNull();
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(productId);
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.productName()).isNull();
            assertThat(item.unitPrice()).isNull();
        });
    }

    @Test
    void shouldRejectDuplicateProductBeforeCallingPersistence() {
        var port = mock(OrderCreationPort.class);
        var requestHasher = new OrderCreationRequestHasher();
        var service = new CreateOrderService(
                port,
                requestHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var productId = UUID.randomUUID();
        var items = List.of(
                new CreateOrderService.ItemCommand(productId, 1),
                new CreateOrderService.ItemCommand(productId, 2)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        "checkout-test-duplicate",
                        UUID.randomUUID(),
                        items
                ))
                .isInstanceOf(DuplicateProductException.class)
                .hasMessageContaining(productId.toString());

        verifyNoInteractions(port);
    }

    @Test
    void shouldRejectInvalidIdempotencyKeyBeforeCallingPersistence() {
        var port = mock(OrderCreationPort.class);
        var requestHasher = new OrderCreationRequestHasher();
        var service = new CreateOrderService(
                port,
                requestHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var items = List.of(new CreateOrderService.ItemCommand(UUID.randomUUID(), 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        "invalid key with spaces",
                        UUID.randomUUID(),
                        items
                ))
                .isInstanceOf(InvalidIdempotencyKeyException.class);

        verify(port, never()).createOrReplay(any(), any(), any(), any());
    }
}
