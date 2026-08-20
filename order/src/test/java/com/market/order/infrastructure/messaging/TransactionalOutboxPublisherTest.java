package com.market.order.infrastructure.messaging;

import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionalOutboxPublisherTest {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @Mock
    private OutboxMessageRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void shouldPublishThePersistedRoutePayloadAndHeaders() {
        var claimedMessage = claimedMessage(1);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(Optional.of(claimedMessage), Optional.empty());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(claimedMessage)).thenReturn(true);

        publisher(50, 5).publishBatch();

        var recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        var record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("market.inventory.commands.reserve.v1");
        assertThat(record.key()).isEqualTo(claimedMessage.message().partitionKey());
        assertThat(record.value()).isEqualTo(claimedMessage.message().payload());
        assertThat(record.headers()).hasSize(2);
        assertThat(header(record, "messageId"))
                .isEqualTo(claimedMessage.message().messageId().toString());
        assertThat(header(record, "messageType")).isEqualTo("ReserveInventory");
        verify(repository).markPublished(claimedMessage);
        verify(repository, never()).markFailed(any(), anyInt(), any(), any());
    }

    @Test
    void shouldRecordFailureAndContinueWithTheNextEligibleMessage() {
        var failedMessage = claimedMessage(2);
        var nextMessage = claimedMessage(1);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(
                        Optional.of(failedMessage),
                        Optional.of(nextMessage),
                        Optional.empty()
                );
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")
                ))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markFailed(
                eq(failedMessage),
                eq(5),
                eq(RETRY_DELAY),
                eq("broker unavailable")
        )).thenReturn(true);
        when(repository.markPublished(nextMessage)).thenReturn(true);

        publisher(50, 5).publishBatch();

        verify(repository).markFailed(
                eq(failedMessage),
                eq(5),
                eq(RETRY_DELAY),
                eq("broker unavailable")
        );
        verify(repository).markPublished(nextMessage);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(any(ProducerRecord.class));
        verify(repository, org.mockito.Mockito.times(3))
                .claimNext(eq(5), eq(LEASE_DURATION));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRestoreTheInterruptFlagAndStopTheBatch() throws Exception {
        var claimedMessage = claimedMessage(1);
        var interruptedSend = mock(CompletableFuture.class);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(Optional.of(claimedMessage));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(interruptedSend);
        when(interruptedSend.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("shutdown"));
        when(repository.markFailed(
                eq(claimedMessage),
                eq(5),
                eq(RETRY_DELAY),
                eq("shutdown")
        )).thenReturn(true);

        try {
            publisher(50, 5).publishBatch();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(repository).claimNext(eq(5), eq(LEASE_DURATION));
            verify(repository).markFailed(
                    eq(claimedMessage),
                    eq(5),
                    eq(RETRY_DELAY),
                    eq("shutdown")
            );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldRespectTheConfiguredBatchLimit() {
        var claimedMessage = claimedMessage(1);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(Optional.of(claimedMessage));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(claimedMessage)).thenReturn(true);

        publisher(1, 5).publishBatch();

        verify(repository).claimNext(eq(5), eq(LEASE_DURATION));
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void shouldNotOverwriteStateWhenThePublishedMessageLostItsLease() {
        var claimedMessage = claimedMessage(1);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(Optional.of(claimedMessage), Optional.empty());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(claimedMessage)).thenReturn(false);

        publisher(50, 5).publishBatch();

        verify(repository).markPublished(claimedMessage);
        verify(repository, never()).markFailed(any(), anyInt(), any(), any());
    }

    @Test
    void shouldNotOverwriteStateWhenTheFailedMessageLostItsLease() {
        var claimedMessage = claimedMessage(1);
        when(repository.claimNext(eq(5), eq(LEASE_DURATION)))
                .thenReturn(Optional.of(claimedMessage), Optional.empty());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")
                ));
        when(repository.markFailed(
                claimedMessage,
                5,
                RETRY_DELAY,
                "broker unavailable"
        )).thenReturn(false);

        publisher(50, 5).publishBatch();

        verify(repository).markFailed(
                claimedMessage,
                5,
                RETRY_DELAY,
                "broker unavailable"
        );
        verify(repository, never()).markPublished(any());
    }

    private TransactionalOutboxPublisher publisher(int batchSize, int maxAttempts) {
        return new TransactionalOutboxPublisher(
                repository,
                kafkaTemplate,
                new OutboxPublisherProperties(
                        batchSize,
                        maxAttempts,
                        RETRY_DELAY,
                        SEND_TIMEOUT,
                        LEASE_DURATION,
                        5_000
                )
        );
    }

    private ClaimedOutboxMessage claimedMessage(int attempt) {
        var messageId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var headers = new LinkedHashMap<String, String>();
        headers.put("messageId", messageId.toString());
        headers.put("messageType", "ReserveInventory");
        var message = new OutboxMessage(
                messageId,
                orderId,
                "ORDER",
                new MessageContract(MessageCategory.COMMAND, "ReserveInventory", 1),
                "order",
                "market.inventory.commands.reserve.v1",
                orderId.toString(),
                orderId,
                null,
                headers,
                "{\"messageId\":\"" + messageId + "\"}",
                Instant.parse("2026-08-20T20:00:00Z")
        );
        return new ClaimedOutboxMessage(message, attempt, UUID.randomUUID());
    }

    private String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
