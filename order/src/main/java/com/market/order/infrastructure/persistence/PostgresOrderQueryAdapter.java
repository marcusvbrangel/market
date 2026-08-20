package com.market.order.infrastructure.persistence;

import com.market.order.application.port.OrderQueryPort;
import com.market.order.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class PostgresOrderQueryAdapter implements OrderQueryPort {

    private final SpringDataOrderRepository repository;

    PostgresOrderQueryAdapter(SpringDataOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(OrderJpaEntity::toDomain);
    }
}
