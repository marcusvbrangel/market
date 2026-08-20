package com.market.order.infrastructure.messaging;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class OutboxMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("1234");

    @Test
    void shouldBackfillLegacyOrderCreatedWithoutLosingDeliveryState() {
        var schema = randomSchema();
        flyway(schema, "5").migrate();
        var jdbcClient = jdbcClient(schema);
        var eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var orderId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var occurredAt = Instant.parse("2026-08-20T20:15:30.120Z");
        var nextAttemptAt = Instant.parse("2026-08-20T20:20:30.123456Z");
        var payload = earliestLegacyPayload(eventId, orderId, occurredAt);
        jdbcClient.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, aggregate_type, event_type,
                            payload, status, attempts, occurred_at, created_at,
                            next_attempt_at, last_error
                        ) VALUES (
                            :eventId, :orderId, 'ORDER', 'OrderCreated',
                            CAST(:payload AS jsonb), 'PENDING', 2, :occurredAt, :createdAt,
                            :nextAttemptAt, 'temporary failure'
                        )
                        """)
                .param("eventId", eventId)
                .param("orderId", orderId)
                .param("payload", payload)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("createdAt", Timestamp.from(occurredAt.minusSeconds(60)))
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .update();
        var legacyPublishedPayload = jdbcClient.sql("""
                        SELECT payload::text
                        FROM outbox_events
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .single();

        flyway(schema, null).migrate();

        assertThat(jdbcClient.sql("""
                        SELECT concat_ws('|',
                            message_id::text,
                            message_category,
                            message_type,
                            schema_version::text,
                            source,
                            destination_topic,
                            partition_key,
                            correlation_id::text,
                            status,
                            attempts::text,
                            last_error
                        )
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", eventId)
                .query(String.class)
                .single()).isEqualTo(
                eventId + "|EVENT|OrderCreated|1|order|market.order.events.created.v1|"
                        + orderId + "|" + orderId + "|PENDING|2|temporary failure"
        );
        assertThat(jdbcClient.sql("""
                        SELECT next_attempt_at = :nextAttemptAt
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("messageId", eventId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT headers = jsonb_build_object(
                            'eventId', :eventId,
                            'eventType', 'OrderCreated',
                            'schemaVersion', '1',
                            'correlationId', :orderId,
                            'occurredAt', :occurredAt
                        )
                        FROM outbox_messages
                        WHERE message_id = CAST(:eventId AS uuid)
                        """)
                .param("eventId", eventId.toString())
                .param("orderId", orderId.toString())
                .param("occurredAt", occurredAt.toString())
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT payload = :payload
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("payload", legacyPublishedPayload)
                .param("messageId", eventId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(tableExists(jdbcClient, "outbox_events")).isFalse();
        assertThat(tableExists(jdbcClient, "outbox_messages")).isTrue();

        jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE message_id = :messageId
                        """)
                .param("messageId", eventId)
                .update();

        var repository = new OutboxMessageRepository(jdbcClient, JsonMapper.shared());
        var migratedClaim = repository.claimNext(5, Duration.ofSeconds(30)).orElseThrow();

        assertThat(migratedClaim.attempt()).isEqualTo(3);
        assertThat(migratedClaim.message().messageId()).isEqualTo(eventId);
        assertThat(migratedClaim.message().destinationTopic())
                .isEqualTo("market.order.events.created.v1");
        assertThat(migratedClaim.message().headers()).containsEntry("eventId", eventId.toString());
        assertThat(migratedClaim.message().payload()).isEqualTo(legacyPublishedPayload);
    }

    @Test
    void shouldPreserveTerminalRowsAndRecoverLegacyProcessingRows() {
        var schema = randomSchema();
        flyway(schema, "5").migrate();
        var jdbcClient = jdbcClient(schema);
        var orderId = UUID.randomUUID();
        var publishedMessageId = UUID.randomUUID();
        var failedMessageId = UUID.randomUUID();
        var processingMessageId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-20T20:15:30.123456Z");
        var publishedAt = Instant.parse("2026-08-20T20:16:30.123456Z");
        insertLegacyMessage(
                jdbcClient,
                publishedMessageId,
                orderId,
                occurredAt,
                "PUBLISHED",
                1,
                publishedAt,
                null
        );
        insertLegacyMessage(
                jdbcClient,
                failedMessageId,
                orderId,
                occurredAt,
                "FAILED",
                5,
                null,
                "attempts exhausted"
        );
        insertLegacyMessage(
                jdbcClient,
                processingMessageId,
                orderId,
                occurredAt,
                "PROCESSING",
                2,
                null,
                null
        );

        flyway(schema, null).migrate();

        assertThat(deliveryState(jdbcClient, publishedMessageId))
                .isEqualTo("PUBLISHED|1|true|false");
        assertThat(deliveryState(jdbcClient, failedMessageId))
                .isEqualTo("FAILED|5|false|false|attempts exhausted");
        assertThat(deliveryState(jdbcClient, processingMessageId))
                .isEqualTo("PROCESSING|2|false|true");
        assertThat(jdbcClient.sql("""
                        SELECT published_at = :publishedAt
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("messageId", publishedMessageId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT lease_until <= CURRENT_TIMESTAMP
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", processingMessageId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT headers ->> 'occurredAt'
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", processingMessageId)
                .query(String.class)
                .single()).isEqualTo(occurredAt.toString());
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM outbox_messages
                        WHERE destination_topic = 'market.order.events.created.v1'
                        """)
                .query(Long.class)
                .single()).isEqualTo(3L);
    }

    @Test
    void shouldAbortUpgradeWhenALegacyContractCannotBeRoutedSafely() {
        var schema = randomSchema();
        flyway(schema, "5").migrate();
        var jdbcClient = jdbcClient(schema);
        var messageId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-20T20:15:30Z");
        jdbcClient.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, aggregate_type, event_type,
                            payload, status, attempts, occurred_at, created_at
                        ) VALUES (
                            :messageId, :orderId, 'ORDER', 'UnknownEvent',
                            CAST(:payload AS jsonb), 'PENDING', 0, :occurredAt, :occurredAt
                        )
                        """)
                .param("messageId", messageId)
                .param("orderId", orderId)
                .param("payload", "{\"eventId\":\"" + messageId + "\"}")
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "V6 cannot route an unknown or inconsistent legacy outbox contract"
                );
        assertThat(tableExists(jdbcClient, "outbox_events")).isTrue();
        assertThat(tableExists(jdbcClient, "outbox_messages")).isFalse();
    }

    @Test
    void shouldAbortUpgradeWhenLegacyMetadataIsOnlyPartiallyPresent() {
        var schema = randomSchema();
        flyway(schema, "5").migrate();
        var jdbcClient = jdbcClient(schema);
        var messageId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-20T20:15:30Z");
        var payload = earliestLegacyPayload(messageId, orderId, occurredAt)
                .replace(
                        "\"eventId\"",
                        "\"eventType\": \"OrderCreated\",\n  \"eventId\""
                );
        jdbcClient.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, aggregate_type, event_type,
                            payload, status, attempts, occurred_at, created_at
                        ) VALUES (
                            :messageId, :orderId, 'ORDER', 'OrderCreated',
                            CAST(:payload AS jsonb), 'PENDING', 0, :occurredAt, :occurredAt
                        )
                        """)
                .param("messageId", messageId)
                .param("orderId", orderId)
                .param("payload", payload)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "V6 cannot route an unknown or inconsistent legacy outbox contract"
                );
        assertThat(tableExists(jdbcClient, "outbox_events")).isTrue();
        assertThat(tableExists(jdbcClient, "outbox_messages")).isFalse();
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration");

        if (target != null) {
            configuration.target(target);
        }

        return configuration.load();
    }

    private JdbcClient jdbcClient(String schema) {
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        var url = POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
        var dataSource = new DriverManagerDataSource(
                url,
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        return JdbcClient.create(dataSource);
    }

    private boolean tableExists(JdbcClient jdbcClient, String tableName) {
        return jdbcClient.sql("SELECT to_regclass(:tableName) IS NOT NULL")
                .param("tableName", tableName)
                .query(Boolean.class)
                .single();
    }

    private void insertLegacyMessage(
            JdbcClient jdbcClient,
            UUID messageId,
            UUID orderId,
            Instant occurredAt,
            String status,
            int attempts,
            Instant publishedAt,
            String lastError
    ) {
        jdbcClient.sql("""
                        INSERT INTO outbox_events (
                            id, aggregate_id, aggregate_type, event_type,
                            payload, status, attempts, occurred_at, created_at,
                            published_at, last_error
                        ) VALUES (
                            :messageId, :orderId, 'ORDER', 'OrderCreated',
                            CAST(:payload AS jsonb), :status, :attempts,
                            :occurredAt, :occurredAt, :publishedAt, :lastError
                        )
                        """)
                .param("messageId", messageId)
                .param("orderId", orderId)
                .param("payload", legacyPayload(messageId, orderId, occurredAt))
                .param("status", status)
                .param("attempts", attempts)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param(
                        "publishedAt",
                        publishedAt == null ? null : publishedAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .param("lastError", lastError, Types.VARCHAR)
                .update();
    }

    private String deliveryState(JdbcClient jdbcClient, UUID messageId) {
        return jdbcClient.sql("""
                        SELECT concat_ws('|',
                            status,
                            attempts::text,
                            (published_at IS NOT NULL)::text,
                            (lease_id IS NOT NULL AND lease_until IS NOT NULL)::text,
                            last_error
                        )
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .query(String.class)
                .single();
    }

    private String legacyPayload(UUID eventId, UUID orderId, Instant occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "OrderCreated",
                  "schemaVersion": 1,
                  "correlationId": "%s",
                  "orderId": "%s",
                  "customerId": "33333333-3333-3333-3333-333333333333",
                  "items": [{
                    "productId": "44444444-4444-4444-4444-444444444444",
                    "quantity": 2
                  }],
                  "occurredAt": "%s"
                }
                """.formatted(eventId, orderId, orderId, occurredAt);
    }

    private String earliestLegacyPayload(UUID eventId, UUID orderId, Instant occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "customerId": "33333333-3333-3333-3333-333333333333",
                  "items": [{
                    "productId": "44444444-4444-4444-4444-444444444444",
                    "quantity": 2
                  }],
                  "occurredAt": "%s"
                }
                """.formatted(eventId, orderId, occurredAt);
    }

    private String randomSchema() {
        return "migration_" + UUID.randomUUID().toString().replace("-", "");
    }
}
