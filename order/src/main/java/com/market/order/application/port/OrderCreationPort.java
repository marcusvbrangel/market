package com.market.order.application.port;

import com.market.order.application.OrderCreatedEvent;
import com.market.order.domain.Order;

public interface OrderCreationPort {

    Order save(Order order, OrderCreatedEvent event);
}
