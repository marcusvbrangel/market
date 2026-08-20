package com.market.order.infrastructure.persistence;

import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.port.OrderCreationPort;
import com.market.order.domain.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;

@Repository
class PostgresOrderCreationAdapter implements OrderCreationPort {

    private final SpringDataOrderRepository repository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    PostgresOrderCreationAdapter(
            SpringDataOrderRepository repository,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Order save(Order order, OrderCreatedEvent event) {
        repository.save(OrderJpaEntity.fromDomain(order));
        insertOutbox(event);
        return order;
    }

    private void insertOutbox(OrderCreatedEvent event) {
        jdbcClient.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, aggregate_type, event_type,
                            payload, status, attempts, occurred_at, created_at
                        ) VALUES (
                            :id, :aggregateId, 'ORDER', 'OrderCreated',
                            CAST(:payload AS jsonb), 'PENDING', 0, :occurredAt, :occurredAt
                        )
                        """)
                .param("id", event.eventId())
                .param("aggregateId", event.orderId())
                .param("payload", serialize(event))
                .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    private String serialize(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize OrderCreated outbox event", exception);
        }
    }
}
