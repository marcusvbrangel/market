package com.market.order.application;

import com.market.order.application.port.OrderCreationPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void shouldCreatePendingUnpricedOrderAndOutboxEvent() {
        var port = mock(OrderCreationPort.class);
        when(port.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new CreateOrderService(
                port,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var order = service.create(
                customerId,
                List.of(new CreateOrderService.ItemCommand(productId, 2))
        );

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
        verify(port).save(any(), any(OrderCreatedEvent.class));
    }
}
