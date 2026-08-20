package com.market.order.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "market.outbox.publisher")
record OutboxPublisherProperties(
        int batchSize,
        int maxAttempts,
        Duration retryDelay,
        Duration sendTimeout,
        Duration leaseDuration,
        long kafkaMaxBlockMilliseconds
) {

    private static final Duration LEASE_SAFETY_MARGIN = Duration.ofSeconds(5);

    OutboxPublisherProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Outbox batch size must be greater than zero");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("Outbox max attempts must be greater than zero");
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Outbox retry delay must not be negative");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("Outbox send timeout must be greater than zero");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Outbox lease duration must be greater than zero");
        }
        if (kafkaMaxBlockMilliseconds <= 0) {
            throw new IllegalArgumentException("Kafka max block time must be greater than zero");
        }

        var minimumSendBudget = sendTimeout
                .plusMillis(kafkaMaxBlockMilliseconds)
                .plus(LEASE_SAFETY_MARGIN);

        if (leaseDuration.compareTo(minimumSendBudget) <= 0) {
            throw new IllegalArgumentException(
                    "Outbox lease duration must exceed the complete Kafka send budget"
            );
        }
    }
}
