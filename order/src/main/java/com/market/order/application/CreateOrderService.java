package com.market.order.application;

import com.market.order.application.port.OrderCreationPort;
import com.market.order.domain.Order;
import com.market.order.domain.OrderItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class CreateOrderService {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final OrderCreationPort orderCreationPort;
    private final Clock clock;

    @Autowired
    public CreateOrderService(OrderCreationPort orderCreationPort) {
        this(orderCreationPort, Clock.systemUTC());
    }

    CreateOrderService(OrderCreationPort orderCreationPort, Clock clock) {
        this.orderCreationPort = orderCreationPort;
        this.clock = clock;
    }

    @Transactional
    public Order create(UUID customerId, List<ItemCommand> requestedItems) {
        var now = Instant.now(clock);
        var orderId = UUID.randomUUID();
        var items = requestedItems.stream()
                .map(item -> OrderItem.requested(UUID.randomUUID(), item.productId(), item.quantity()))
                .toList();
        var order = Order.pending(orderId, orderNumber(orderId, now), customerId, items, now);
        var event = new OrderCreatedEvent(
                UUID.randomUUID(),
                orderId,
                customerId,
                requestedItems.stream()
                        .map(item -> new OrderCreatedEvent.Item(item.productId(), item.quantity()))
                        .toList(),
                now
        );
        return orderCreationPort.save(order, event);
    }

    private String orderNumber(UUID orderId, Instant createdAt) {
        return "ORD-" + ORDER_DATE.format(createdAt) + "-"
                + orderId.toString().substring(0, 8).toUpperCase();
    }

    public record ItemCommand(UUID productId, int quantity) {
    }
}
