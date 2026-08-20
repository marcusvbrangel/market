package com.market.order.infrastructure.messaging;

import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.messaging.MessageContract;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
class KafkaMessageRouteRegistry {

    private final Map<MessageContract, MessageRoute> routes;

    KafkaMessageRouteRegistry(KafkaTopicProperties topics) {
        this.routes = Map.of(
                OrderCreatedEvent.contractDefinition(),
                new MessageRoute(topics.orderCreatedEvents())
        );
    }

    MessageRoute routeFor(MessageContract contract) {
        Objects.requireNonNull(contract, "Message contract must not be null");

        var route = routes.get(contract);

        if (route == null) {
            throw new IllegalArgumentException("No Kafka route configured for " + contract);
        }

        return route;
    }
}
