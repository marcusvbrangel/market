package com.market.order.infrastructure.persistence;

import com.market.order.application.port.OrderQueryPort;
import com.market.order.domain.Order;
import com.market.order.domain.OrderItem;
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
        return repository.findById(orderId).map(this::toDomain);
    }

    private Order toDomain(OrderJpaEntity entity) {
        var items = entity.getItems().stream()
                .map(item -> new OrderItem(
                        item.getId(),
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
                .toList();

        return new Order(
                entity.getId(),
                entity.getOrderNumber(),
                entity.getCustomerId(),
                entity.getStatus(),
                items,
                entity.getTotalAmount(),
                entity.getCurrency(),
                entity.getRejectionReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
