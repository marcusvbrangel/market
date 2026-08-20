package com.market.order.infrastructure.persistence;

import com.market.order.domain.Order;
import com.market.order.domain.OrderItem;
import com.market.order.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 3)
    private String currency;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderJpaEntity() {
    }

    static OrderJpaEntity fromDomain(Order order) {
        var entity = new OrderJpaEntity();
        entity.id = order.id();
        entity.orderNumber = order.orderNumber();
        entity.customerId = order.customerId();
        entity.status = order.status();
        entity.totalAmount = order.totalAmount();
        entity.currency = order.currency();
        entity.rejectionReason = order.rejectionReason();
        entity.createdAt = order.createdAt();
        entity.updatedAt = order.updatedAt();
        for (int position = 0; position < order.items().size(); position++) {
            entity.items.add(new OrderItemJpaEntity(
                    entity, order.items().get(position), position
            ));
        }
        return entity;
    }

    Order toDomain() {
        var domainItems = new ArrayList<OrderItem>();

        for (var item : items) {
            var domainItem = new OrderItem(
                    item.getId(),
                    item.getProductId(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getSubtotal()
            );
            domainItems.add(domainItem);
        }

        return new Order(
                id,
                orderNumber,
                customerId,
                status,
                domainItems,
                totalAmount,
                currency,
                rejectionReason,
                createdAt,
                updatedAt
        );
    }

}
