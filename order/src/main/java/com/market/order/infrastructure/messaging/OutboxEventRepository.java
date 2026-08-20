package com.market.order.infrastructure.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class OutboxEventRepository {

    private final JdbcClient jdbcClient;

    OutboxEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<OutboxEvent> lockPublishable(int batchSize) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, payload::text, attempts, occurred_at
                        FROM outbox_events
                        WHERE status = 'PENDING'
                          AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                        ORDER BY created_at
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", batchSize)
                .query((resultSet, rowNumber) -> new OutboxEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts"),
                        resultSet.getTimestamp("occurred_at").toInstant()
                ))
                .list();
    }

    void markPublished(UUID eventId, Instant publishedAt) {
        jdbcClient.sql("""
                        UPDATE outbox_events
                        SET status = 'PUBLISHED', attempts = attempts + 1,
                            published_at = :publishedAt, next_attempt_at = NULL, last_error = NULL
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .param("publishedAt", Timestamp.from(publishedAt))
                .update();
    }

    void markFailed(UUID eventId, int currentAttempts, int maxAttempts, Duration retryDelay, String error) {
        var nextAttempts = currentAttempts + 1;
        var terminal = nextAttempts >= maxAttempts;
        if (terminal) {
            jdbcClient.sql("""
                            UPDATE outbox_events
                            SET status = 'FAILED', attempts = :attempts,
                                next_attempt_at = NULL, last_error = :lastError
                            WHERE id = :eventId
                            """)
                    .param("attempts", nextAttempts)
                    .param("lastError", abbreviate(error))
                    .param("eventId", eventId)
                    .update();
            return;
        }
        jdbcClient.sql("""
                        UPDATE outbox_events
                        SET status = 'PENDING', attempts = :attempts,
                            next_attempt_at = :nextAttemptAt, last_error = :lastError
                        WHERE id = :eventId
                        """)
                .param("attempts", nextAttempts)
                .param("nextAttemptAt", Timestamp.from(Instant.now().plus(retryDelay)))
                .param("lastError", abbreviate(error))
                .param("eventId", eventId)
                .update();
    }

    private String abbreviate(String error) {
        var message = error == null || error.isBlank() ? "Unknown Kafka publishing failure" : error;
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
