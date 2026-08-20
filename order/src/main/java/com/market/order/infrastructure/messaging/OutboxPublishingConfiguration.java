package com.market.order.infrastructure.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxPublisherProperties.class, KafkaTopicProperties.class})
class OutboxPublishingConfiguration {
}
