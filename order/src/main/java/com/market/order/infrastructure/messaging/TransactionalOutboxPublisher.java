package com.market.order.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "market.outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
class TransactionalOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalOutboxPublisher.class);

    private final OutboxMessageRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxPublisherProperties properties;

    TransactionalOutboxPublisher(
            OutboxMessageRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxPublisherProperties properties
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${market.outbox.publisher.fixed-delay:1000}")
    public void publishBatch() {
        for (var processed = 0; processed < properties.batchSize(); processed++) {
            var claimedMessage = repository.claimNext(
                    properties.maxAttempts(),
                    properties.leaseDuration()
            );

            if (claimedMessage.isEmpty()) {
                return;
            }

            var shouldContinue = publish(claimedMessage.orElseThrow());

            if (!shouldContinue) {
                return;
            }
        }
    }

    private boolean publish(ClaimedOutboxMessage claimedMessage) {
        var message = claimedMessage.message();

        try {
            var record = new ProducerRecord<>(
                    message.destinationTopic(),
                    message.partitionKey(),
                    message.payload()
            );

            for (var header : message.headers().entrySet()) {
                record.headers().add(
                        header.getKey(),
                        header.getValue().getBytes(StandardCharsets.UTF_8)
                );
            }

            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            var markedAsPublished = repository.markPublished(claimedMessage);

            if (!markedAsPublished) {
                LOGGER.warn("Lost outbox lease after publishing message id={}", message.messageId());
                return true;
            }

            LOGGER.info(
                    "Published outbox message id={} type={} destination={} aggregateId={}",
                    message.messageId(),
                    message.contract().messageType(),
                    message.destinationTopic(),
                    message.aggregateId()
            );
            return true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            var failureRecorded = repository.markFailed(
                    claimedMessage,
                    properties.maxAttempts(),
                    properties.retryDelay(),
                    rootMessage(exception)
            );

            if (!failureRecorded) {
                LOGGER.warn("Lost outbox lease after publishing failure message id={}", message.messageId());
            }

            LOGGER.warn(
                    "Failed to publish outbox message id={} attempt={}",
                    message.messageId(),
                    claimedMessage.attempt(),
                    exception
            );
            return !(exception instanceof InterruptedException);
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
