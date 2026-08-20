package com.market.order.application.port;

import com.market.order.application.CreateOrderResult;
import com.market.order.application.IdempotencyKey;
import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.OrderCreationRequestFingerprint;
import com.market.order.domain.Order;

public interface OrderCreationPort {

    CreateOrderResult createOrReplay(
            Order order,
            OrderCreatedEvent event,
            IdempotencyKey idempotencyKey,
            OrderCreationRequestFingerprint requestFingerprint
    );
}
