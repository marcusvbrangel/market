package com.market.order.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "market.outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
class TransactionalOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalOutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topics;
    private final OutboxPublisherProperties properties;

    TransactionalOutboxPublisher(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaTopicProperties topics,
            OutboxPublisherProperties properties
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${market.outbox.publisher.fixed-delay:1000}")
    @Transactional
    public void publishBatch() {
        repository.lockPublishable(properties.batchSize()).forEach(this::publish);
    }

    private void publish(OutboxEvent event) {
        try {
            var record = new ProducerRecord<>(
                    topics.orderEvents(),
                    event.aggregateId().toString(),
                    event.payload()
            );
            record.headers()
                    .add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8))
                    .add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8))
                    .add("schemaVersion", "1".getBytes(StandardCharsets.UTF_8))
                    .add("correlationId", event.aggregateId().toString().getBytes(StandardCharsets.UTF_8))
                    .add("occurredAt", event.occurredAt().toString().getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            repository.markPublished(event.id(), Instant.now());
            LOGGER.info("Published outbox event id={} type={} aggregateId={}",
                    event.id(), event.eventType(), event.aggregateId());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            repository.markFailed(
                    event.id(), event.attempts(), properties.maxAttempts(),
                    properties.retryDelay(), rootMessage(exception)
            );
            LOGGER.warn("Failed to publish outbox event id={} attempt={}",
                    event.id(), event.attempts() + 1, exception);
        }
    }

    private String rootMessage(Exception exception) {
        var cause = exception;
        while (cause.getCause() instanceof Exception nested) {
            cause = nested;
        }
        return cause.getMessage();
    }
}
