package com.market.order.infrastructure.messaging;

import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "market.outbox.publisher.enabled=false")
@Testcontainers
class OutboxMessageRepositoryTests {

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("1234");

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Autowired
    private OutboxPublisherProperties publisherProperties;

    @Autowired
    private OrderCreatedOutboxWriter orderCreatedOutboxWriter;

    @BeforeEach
    void cleanOutbox() {
        jdbcClient.sql("DELETE FROM outbox_messages").update();
    }

    @Test
    void shouldClaimAndPublishWithAShortRecoverableLease() {
        var message = message();
        repository.append(message);

        var claimedMessage = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();

        assertThat(claimedMessage.message()).isEqualTo(message);
        assertThat(claimedMessage.attempt()).isEqualTo(1);
        assertThat(status(message.messageId())).isEqualTo("PROCESSING");
        assertThat(repository.markPublished(claimedMessage)).isTrue();
        assertThat(status(message.messageId())).isEqualTo("PUBLISHED");
        assertThat(hasLease(message.messageId())).isFalse();
    }

    @Test
    void shouldUseTheDatabaseClockForNewMessageEligibility() {
        var message = message(Instant.parse("2099-08-20T19:59:00Z"));
        repository.append(message);

        var claimedMessage = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();

        assertThat(claimedMessage.message().occurredAt()).isEqualTo(message.occurredAt());
    }

    @Test
    void shouldKeepTheLeaseBudgetAlignedWithTheKafkaProducer() {
        var configuredMaxBlock = producerFactory
                .getConfigurationProperties()
                .get(ProducerConfig.MAX_BLOCK_MS_CONFIG);

        assertThat(Long.parseLong(configuredMaxBlock.toString()))
                .isEqualTo(publisherProperties.kafkaMaxBlockMilliseconds());
    }

    @Test
    void shouldRequireAnExistingTransactionToAppendOrderCreated() {
        var orderId = UUID.randomUUID();
        var event = new OrderCreatedEvent(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                List.of(new OrderCreatedEvent.Item(UUID.randomUUID(), 1)),
                Instant.now()
        );

        assertThatThrownBy(() -> orderCreatedOutboxWriter.append(event))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void shouldRetryAtTheScheduledTimeAndThenFailTerminally() {
        var message = message();
        repository.append(message);
        var firstClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();

        assertThat(repository.markFailed(
                firstClaim,
                MAX_ATTEMPTS,
                RETRY_DELAY,
                "broker unavailable"
        )).isTrue();
        assertThat(status(message.messageId())).isEqualTo("PENDING");
        assertThat(repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        )).isEmpty();

        makeRetryEligible(message.messageId());

        var secondClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();
        assertThat(secondClaim.attempt()).isEqualTo(2);
        assertThat(repository.markFailed(
                secondClaim,
                MAX_ATTEMPTS,
                RETRY_DELAY,
                "broker still unavailable"
        )).isTrue();
        assertThat(status(message.messageId())).isEqualTo("FAILED");
        assertThat(hasNextAttempt(message.messageId())).isFalse();
        assertThat(hasLease(message.messageId())).isFalse();
    }

    @Test
    void shouldClearRetryMetadataAfterASuccessfulRetry() {
        var message = message();
        repository.append(message);
        var firstClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();
        repository.markFailed(
                firstClaim,
                MAX_ATTEMPTS,
                RETRY_DELAY,
                "temporary failure"
        );
        makeRetryEligible(message.messageId());

        var retryClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();

        assertThat(repository.markPublished(retryClaim)).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT published_at IS NOT NULL
                            AND next_attempt_at IS NULL
                            AND last_error IS NULL
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", message.messageId())
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void shouldRecoverAnExpiredLeaseAndRejectTheOldOwner() {
        var message = message();
        repository.append(message);
        var firstClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();

        assertThat(repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        )).isEmpty();

        expireLease(message.messageId());

        var recoveredClaim = repository.claimNext(
                MAX_ATTEMPTS,
                LEASE_DURATION
        ).orElseThrow();
        assertThat(recoveredClaim.attempt()).isEqualTo(2);
        assertThat(recoveredClaim.leaseId()).isNotEqualTo(firstClaim.leaseId());
        assertThat(repository.markPublished(firstClaim)).isFalse();
        assertThat(repository.markFailed(
                firstClaim,
                MAX_ATTEMPTS,
                RETRY_DELAY,
                "stale owner"
        )).isFalse();
        assertThat(repository.markPublished(recoveredClaim)).isTrue();
        assertThat(status(message.messageId())).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldFailAnExpiredLastClaimWithoutClaimingItAgain() {
        var message = message();
        repository.append(message);
        var lastClaim = repository.claimNext(1, LEASE_DURATION).orElseThrow();
        assertThat(lastClaim.attempt()).isEqualTo(1);
        expireLease(message.messageId());

        assertThat(repository.claimNext(1, LEASE_DURATION)).isEmpty();
        assertThat(status(message.messageId())).isEqualTo("FAILED");
        assertThat(hasLease(message.messageId())).isFalse();
    }

    @Test
    void shouldRejectHeadersThatCannotBePublishedAsText() {
        var message = message();
        repository.append(message);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET headers = '{"messageId": 1}'::jsonb
                        WHERE message_id = :messageId
                        """)
                .param("messageId", message.messageId())
                .update())
                .rootCause()
                .hasMessageContaining("ck_outbox_messages_headers");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET headers = '{" ": "value"}'::jsonb
                        WHERE message_id = :messageId
                        """)
                .param("messageId", message.messageId())
                .update())
                .rootCause()
                .hasMessageContaining("ck_outbox_messages_headers");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET payload = '[]'
                        WHERE message_id = :messageId
                        """)
                .param("messageId", message.messageId())
                .update())
                .rootCause()
                .hasMessageContaining("ck_outbox_messages_payload");
    }

    private OutboxMessage message() {
        return message(Instant.parse("2026-08-20T19:59:00Z"));
    }

    private OutboxMessage message(Instant occurredAt) {
        var messageId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var headers = new LinkedHashMap<String, String>();
        headers.put("messageId", messageId.toString());
        headers.put("messageType", "TestCommand");
        return new OutboxMessage(
                messageId,
                orderId,
                "ORDER",
                new MessageContract(MessageCategory.COMMAND, "TestCommand", 1),
                "order",
                "market.test.commands.v1",
                orderId.toString(),
                orderId,
                null,
                headers,
                "{\"messageId\":\"" + messageId + "\"}",
                occurredAt
        );
    }

    private String status(UUID messageId) {
        return jdbcClient.sql("""
                        SELECT status
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .query(String.class)
                .single();
    }

    private boolean hasLease(UUID messageId) {
        return jdbcClient.sql("""
                        SELECT lease_id IS NOT NULL OR lease_until IS NOT NULL
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .query(Boolean.class)
                .single();
    }

    private boolean hasNextAttempt(UUID messageId) {
        return jdbcClient.sql("""
                        SELECT next_attempt_at IS NOT NULL
                        FROM outbox_messages
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .query(Boolean.class)
                .single();
    }

    private void makeRetryEligible(UUID messageId) {
        jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .update();
    }

    private void expireLease(UUID messageId) {
        jdbcClient.sql("""
                        UPDATE outbox_messages
                        SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE message_id = :messageId
                        """)
                .param("messageId", messageId)
                .update();
    }
}
