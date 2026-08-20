package com.market.order.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.kafka.topics")
record KafkaTopicProperties(String orderEvents) {

    KafkaTopicProperties {
        if (orderEvents == null || orderEvents.isBlank()) {
            throw new IllegalArgumentException("Order events topic must be configured");
        }
    }
}
