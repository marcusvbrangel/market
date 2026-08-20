package com.market.order.infrastructure.messaging;

import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class OutboxMessageRepository {

    private static final TypeReference<Map<String, String>> HEADER_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    OutboxMessageRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void append(OutboxMessage message) {
        jdbcClient.sql("""
                        INSERT INTO outbox_messages (
                            message_id, aggregate_id, aggregate_type,
                            message_category, message_type, schema_version,
                            source, destination_topic, partition_key,
                            correlation_id, causation_id, headers, payload,
                            status, attempts, occurred_at, created_at
                        ) VALUES (
                            :messageId, :aggregateId, :aggregateType,
                            :messageCategory, :messageType, :schemaVersion,
                            :source, :destinationTopic, :partitionKey,
                            :correlationId, :causationId,
                            CAST(:headers AS jsonb), :payload,
                            'PENDING', 0, :occurredAt, CURRENT_TIMESTAMP
                        )
                        """)
                .param("messageId", message.messageId())
                .param("aggregateId", message.aggregateId())
                .param("aggregateType", message.aggregateType())
                .param("messageCategory", message.contract().category().name())
                .param("messageType", message.contract().messageType())
                .param("schemaVersion", message.contract().schemaVersion())
                .param("source", message.source())
                .param("destinationTopic", message.destinationTopic())
                .param("partitionKey", message.partitionKey())
                .param("correlationId", message.correlationId())
                .param("causationId", message.causationId(), Types.OTHER)
                .param("headers", serializeHeaders(message.headers()))
                .param("payload", message.payload())
                .param("occurredAt", Timestamp.from(message.occurredAt()))
                .update();
    }

    @Transactional
    public Optional<ClaimedOutboxMessage> claimNext(
            int maxAttempts,
            Duration leaseDuration
    ) {
        markExhaustedClaims(maxAttempts);

        var leaseId = UUID.randomUUID();

        return jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT message_id
                            FROM outbox_messages
                            WHERE attempts < :maxAttempts
                              AND (
                                  (
                                      status = 'PENDING'
                                      AND COALESCE(next_attempt_at, created_at) <= CURRENT_TIMESTAMP
                                  )
                                  OR
                                  (
                                      status = 'PROCESSING'
                                      AND lease_until <= CURRENT_TIMESTAMP
                                  )
                              )
                            ORDER BY
                                CASE
                                    WHEN status = 'PROCESSING' THEN lease_until
                                    ELSE COALESCE(next_attempt_at, created_at)
                                END,
                                created_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE outbox_messages AS message
                        SET status = 'PROCESSING',
                            attempts = message.attempts + 1,
                            next_attempt_at = NULL,
                            lease_id = :leaseId,
                            lease_until = CURRENT_TIMESTAMP
                                + (:leaseDurationMilliseconds * INTERVAL '1 millisecond')
                        FROM candidate
                        WHERE message.message_id = candidate.message_id
                        RETURNING
                            message.message_id,
                            message.aggregate_id,
                            message.aggregate_type,
                            message.message_category,
                            message.message_type,
                            message.schema_version,
                            message.source,
                            message.destination_topic,
                            message.partition_key,
                            message.correlation_id,
                            message.causation_id,
                            message.headers::text,
                            message.payload::text,
                            message.attempts,
                            message.occurred_at,
                            message.lease_id
                        """)
                .param("maxAttempts", maxAttempts)
                .param("leaseId", leaseId)
                .param("leaseDurationMilliseconds", leaseDuration.toMillis())
                .query((resultSet, rowNumber) -> {
                    var contract = new MessageContract(
                            MessageCategory.valueOf(
                                    resultSet.getString("message_category")
                            ),
                            resultSet.getString("message_type"),
                            resultSet.getInt("schema_version")
                    );
                    var message = new OutboxMessage(
                            resultSet.getObject("message_id", UUID.class),
                            resultSet.getObject("aggregate_id", UUID.class),
                            resultSet.getString("aggregate_type"),
                            contract,
                            resultSet.getString("source"),
                            resultSet.getString("destination_topic"),
                            resultSet.getString("partition_key"),
                            resultSet.getObject("correlation_id", UUID.class),
                            resultSet.getObject("causation_id", UUID.class),
                            deserializeHeaders(resultSet.getString("headers")),
                            resultSet.getString("payload"),
                            resultSet.getTimestamp("occurred_at").toInstant()
                    );
                    return new ClaimedOutboxMessage(
                            message,
                            resultSet.getInt("attempts"),
                            resultSet.getObject("lease_id", UUID.class)
                    );
                })
                .optional();
    }

    boolean markPublished(ClaimedOutboxMessage claimedMessage) {
        return jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET status = 'PUBLISHED',
                            published_at = CURRENT_TIMESTAMP,
                            next_attempt_at = NULL,
                            last_error = NULL,
                            lease_id = NULL,
                            lease_until = NULL
                        WHERE message_id = :messageId
                          AND status = 'PROCESSING'
                          AND lease_id = :leaseId
                        """)
                .param("messageId", claimedMessage.message().messageId())
                .param("leaseId", claimedMessage.leaseId())
                .update() == 1;
    }

    boolean markFailed(
            ClaimedOutboxMessage claimedMessage,
            int maxAttempts,
            Duration retryDelay,
            String error
    ) {
        var terminal = claimedMessage.attempt() >= maxAttempts;
        var status = terminal ? "FAILED" : "PENDING";

        return jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET status = :status,
                            next_attempt_at = CASE
                                WHEN :terminal THEN NULL
                                ELSE CURRENT_TIMESTAMP
                                    + (:retryDelayMilliseconds * INTERVAL '1 millisecond')
                            END,
                            last_error = :lastError,
                            lease_id = NULL,
                            lease_until = NULL
                        WHERE message_id = :messageId
                          AND status = 'PROCESSING'
                          AND lease_id = :leaseId
                        """)
                .param("status", status)
                .param("terminal", terminal)
                .param("retryDelayMilliseconds", retryDelay.toMillis())
                .param("lastError", abbreviate(error))
                .param("messageId", claimedMessage.message().messageId())
                .param("leaseId", claimedMessage.leaseId())
                .update() == 1;
    }

    private void markExhaustedClaims(int maxAttempts) {
        jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET status = 'FAILED',
                            next_attempt_at = NULL,
                            last_error = COALESCE(last_error, 'Publishing attempts exhausted'),
                            lease_id = NULL,
                            lease_until = NULL
                        WHERE attempts >= :maxAttempts
                          AND (
                              status = 'PENDING'
                              OR
                              (
                                  status = 'PROCESSING'
                                  AND lease_until <= CURRENT_TIMESTAMP
                              )
                          )
                        """)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    private String serializeHeaders(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize outbox message headers", exception);
        }
    }

    private Map<String, String> deserializeHeaders(String headers) {
        try {
            return objectMapper.readValue(headers, HEADER_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize outbox message headers", exception);
        }
    }

    private String abbreviate(String error) {
        var message = error == null || error.isBlank()
                ? "Unknown Kafka publishing failure"
                : error;
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
