package com.market.order.infrastructure.persistence;

import com.market.order.application.CreateOrderResult;
import com.market.order.application.IdempotencyConflictException;
import com.market.order.application.IdempotencyKey;
import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.OrderCreationRequestFingerprint;
import com.market.order.application.port.OrderCreationPort;
import com.market.order.domain.Order;
import com.market.order.domain.OrderStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
    @Transactional
    public CreateOrderResult createOrReplay(
            Order order,
            OrderCreatedEvent event,
            IdempotencyKey idempotencyKey,
            OrderCreationRequestFingerprint requestFingerprint
    ) {
        var claimed = claimIdempotencyKey(order, idempotencyKey, requestFingerprint);

        if (!claimed) {
            return replayExistingRequest(order.customerId(), idempotencyKey, requestFingerprint);
        }

        var entity = OrderJpaEntity.fromDomain(order);
        repository.saveAndFlush(entity);
        insertOutbox(event);
        return CreateOrderResult.created(order);
    }

    private boolean claimIdempotencyKey(
            Order order,
            IdempotencyKey idempotencyKey,
            OrderCreationRequestFingerprint requestFingerprint
    ) {
        var insertedOrderId = jdbcClient.sql("""
                        INSERT INTO api_idempotency (
                            customer_id, idempotency_key,
                            request_hash_version, request_hash, order_id,
                            response_order_number, response_order_status,
                            response_created_at, created_at
                        ) VALUES (
                            :customerId, :idempotencyKey,
                            :requestHashVersion, :requestHash, :orderId,
                            :orderNumber, :orderStatus,
                            :responseCreatedAt, :createdAt
                        )
                        ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                        RETURNING order_id
                        """)
                .param("customerId", order.customerId())
                .param("idempotencyKey", idempotencyKey.value())
                .param("requestHashVersion", requestFingerprint.version())
                .param("requestHash", requestFingerprint.hash())
                .param("orderId", order.id())
                .param("orderNumber", order.orderNumber())
                .param("orderStatus", order.status().name())
                .param("responseCreatedAt", order.createdAt().atOffset(ZoneOffset.UTC))
                .param("createdAt", order.createdAt().atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional();

        return insertedOrderId.isPresent();
    }

    private CreateOrderResult replayExistingRequest(
            UUID customerId,
            IdempotencyKey idempotencyKey,
            OrderCreationRequestFingerprint requestFingerprint
    ) {
        var existingRequest = findExistingRequest(customerId, idempotencyKey);

        if (existingRequest.requestHashVersion() != requestFingerprint.version()) {
            throw new IdempotencyConflictException();
        }
        if (!existingRequest.requestHash().equals(requestFingerprint.hash())) {
            throw new IdempotencyConflictException();
        }

        return new CreateOrderResult(
                existingRequest.orderId(),
                existingRequest.orderNumber(),
                existingRequest.orderStatus(),
                existingRequest.orderCreatedAt(),
                true
        );
    }

    private IdempotencyRecord findExistingRequest(
            UUID customerId,
            IdempotencyKey idempotencyKey
    ) {
        return jdbcClient.sql("""
                        SELECT
                            request_hash_version,
                            request_hash,
                            order_id,
                            response_order_number,
                            response_order_status,
                            response_created_at
                        FROM api_idempotency
                        WHERE customer_id = :customerId
                          AND idempotency_key = :idempotencyKey
                        """)
                .param("customerId", customerId)
                .param("idempotencyKey", idempotencyKey.value())
                .query((resultSet, rowNumber) -> new IdempotencyRecord(
                        resultSet.getShort("request_hash_version"),
                        resultSet.getString("request_hash"),
                        resultSet.getObject("order_id", UUID.class),
                        resultSet.getString("response_order_number"),
                        OrderStatus.valueOf(resultSet.getString("response_order_status")),
                        resultSet.getObject("response_created_at", OffsetDateTime.class).toInstant()
                ))
                .single();
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

    private record IdempotencyRecord(
            short requestHashVersion,
            String requestHash,
            UUID orderId,
            String orderNumber,
            OrderStatus orderStatus,
            Instant orderCreatedAt
    ) {
    }
}
