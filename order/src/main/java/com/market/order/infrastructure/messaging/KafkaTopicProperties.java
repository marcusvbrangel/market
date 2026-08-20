package com.market.order.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.kafka.topics")
public record KafkaTopicProperties(String orderCreatedEvents) {

    public KafkaTopicProperties {
        if (orderCreatedEvents == null || orderCreatedEvents.isBlank()) {
            throw new IllegalArgumentException("Order created events topic must be configured");
        }
    }
}
