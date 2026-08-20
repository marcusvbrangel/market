package com.market.order.application.port;

import com.market.order.domain.Order;

import java.util.Optional;
import java.util.UUID;

public interface OrderQueryPort {

    Optional<Order> findById(UUID orderId);
}
