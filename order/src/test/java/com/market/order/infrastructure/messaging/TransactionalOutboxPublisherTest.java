package com.market.order.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionalOutboxPublisherTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void shouldPublishWithAggregateKeyAndMarkEventAsPublished() {
        var event = event(0);
        when(repository.lockPublishable(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = publisher(5);

        publisher.publishBatch();

        var recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        var record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("market.order.events.created.v1");
        assertThat(record.key()).isEqualTo(event.aggregateId().toString());
        assertThat(record.value()).isEqualTo(event.payload());
        assertThat(record.headers().lastHeader("eventId").value())
                .asString().isEqualTo(event.id().toString());
        verify(repository).markPublished(org.mockito.ArgumentMatchers.eq(event.id()), any(Instant.class));
        verify(repository, never()).markFailed(any(), any(Integer.class), any(Integer.class), any(), any());
    }

    @Test
    void shouldScheduleRetryWhenKafkaPublishingFails() {
        var event = event(1);
        when(repository.lockPublishable(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        var publisher = publisher(5);

        publisher.publishBatch();

        verify(repository).markFailed(
                event.id(), 1, 5, Duration.ofSeconds(5), "broker unavailable"
        );
        verify(repository, never()).markPublished(any(), any());
    }

    private TransactionalOutboxPublisher publisher(int maxAttempts) {
        return new TransactionalOutboxPublisher(
                repository,
                kafkaTemplate,
                new KafkaTopicProperties("market.order.events.created.v1"),
                new OutboxPublisherProperties(
                        50, maxAttempts, Duration.ofSeconds(5), Duration.ofSeconds(10)
                )
        );
    }

    private OutboxEvent event(int attempts) {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "OrderCreated",
                "{\"eventId\":\"test\"}", attempts, Instant.parse("2026-08-19T20:00:00Z")
        );
    }
}
