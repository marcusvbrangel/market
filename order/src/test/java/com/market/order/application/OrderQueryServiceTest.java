package com.market.order.application;

import com.market.order.application.port.OrderQueryPort;
import com.market.order.domain.Order;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderQueryServiceTest {

    private final OrderQueryPort orderQueryPort = mock(OrderQueryPort.class);
    private final OrderQueryService service = new OrderQueryService(orderQueryPort);

    @Test
    void shouldReturnOrderProvidedByPort() {
        var orderId = UUID.randomUUID();
        var order = mock(Order.class);
        when(orderQueryPort.findById(orderId)).thenReturn(Optional.of(order));

        var result = service.findById(orderId);

        assertThat(result).contains(order);
        verify(orderQueryPort).findById(orderId);
    }

    @Test
    void shouldReturnEmptyWhenOrderDoesNotExist() {
        var orderId = UUID.randomUUID();
        when(orderQueryPort.findById(orderId)).thenReturn(Optional.empty());

        assertThat(service.findById(orderId)).isEmpty();
        verify(orderQueryPort).findById(orderId);
    }
}
