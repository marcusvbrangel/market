package com.market.order.infrastructure.persistence;

import com.market.order.domain.OrderItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
class OrderItemJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderJpaEntity order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false)
    private int position;

    protected OrderItemJpaEntity() {
    }

    OrderItemJpaEntity(
            OrderJpaEntity order,
            OrderItem item,
            int position
    ) {
        this.id = item.id();
        this.order = order;
        this.productId = item.productId();
        this.productName = item.productName();
        this.quantity = item.quantity();
        this.unitPrice = item.unitPrice();
        this.subtotal = item.subtotal();
        this.position = position;
    }

    UUID getId() {
        return id;
    }

    UUID getProductId() {
        return productId;
    }

    String getProductName() {
        return productName;
    }

    int getQuantity() {
        return quantity;
    }

    BigDecimal getUnitPrice() {
        return unitPrice;
    }

    BigDecimal getSubtotal() {
        return subtotal;
    }
}
