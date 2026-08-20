package com.market.order.application;

import com.market.order.application.port.OrderCreationPort;
import com.market.order.domain.Order;
import com.market.order.domain.OrderItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreateOrderService {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final OrderCreationPort orderCreationPort;
    private final OrderCreationRequestHasher requestHasher;
    private final Clock clock;

    @Autowired
    public CreateOrderService(
            OrderCreationPort orderCreationPort,
            OrderCreationRequestHasher requestHasher
    ) {
        this(orderCreationPort, requestHasher, Clock.systemUTC());
    }

    CreateOrderService(
            OrderCreationPort orderCreationPort,
            OrderCreationRequestHasher requestHasher,
            Clock clock
    ) {
        this.orderCreationPort = orderCreationPort;
        this.requestHasher = requestHasher;
        this.clock = clock;
    }

    public CreateOrderResult create(
            String rawIdempotencyKey,
            UUID customerId,
            List<ItemCommand> requestedItems
    ) {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(requestedItems, "Requested items must not be null");

        var safeRequestedItems = List.copyOf(requestedItems);
        var idempotencyKey = IdempotencyKey.from(rawIdempotencyKey);
        validateRequestedItems(safeRequestedItems);
        var requestFingerprint = requestHasher.hash(customerId, safeRequestedItems);

        var now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        var orderId = UUID.randomUUID();

        var items = new ArrayList<OrderItem>();

        for (var requestedItem : safeRequestedItems) {
            var orderItem = createOrderItem(requestedItem);
            items.add(orderItem);
        }

        var order = Order.pending(orderId, orderNumber(orderId, now), customerId, items, now);

        var eventItems = new ArrayList<OrderCreatedEvent.Item>();

        for (var requestedItem : safeRequestedItems) {
            var eventItem = new OrderCreatedEvent.Item(
                    requestedItem.productId(),
                    requestedItem.quantity()
            );
            eventItems.add(eventItem);
        }

        var event = new OrderCreatedEvent(
                UUID.randomUUID(),
                orderId,
                customerId,
                eventItems,
                now
        );

        return orderCreationPort.createOrReplay(
                order,
                event,
                idempotencyKey,
                requestFingerprint
        );
    }

    private void validateRequestedItems(List<ItemCommand> requestedItems) {
        if (requestedItems.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        var productIds = new HashSet<UUID>();

        for (var item : requestedItems) {
            Objects.requireNonNull(item, "Requested item must not be null");
            Objects.requireNonNull(item.productId(), "Product id must not be null");

            if (!productIds.add(item.productId())) {
                throw new DuplicateProductException(item.productId());
            }
        }
    }

    private OrderItem createOrderItem(ItemCommand item) {
        var itemId = UUID.randomUUID();
        return OrderItem.requested(itemId, item.productId(), item.quantity());
    }

    private String orderNumber(UUID orderId, Instant createdAt) {
        return "ORD-" + ORDER_DATE.format(createdAt) + "-"
                + orderId.toString().substring(0, 8).toUpperCase();
    }

    public record ItemCommand(UUID productId, int quantity) {
    }
}
