package com.market.order.infrastructure.messaging;

import com.market.order.application.OrderCreatedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

@Component
class OrderCreatedOutboxMessageFactory {

    private static final String SOURCE = "order";
    private static final String AGGREGATE_TYPE = "ORDER";

    private final ObjectMapper objectMapper;
    private final KafkaMessageRouteRegistry routeRegistry;

    OrderCreatedOutboxMessageFactory(
            ObjectMapper objectMapper,
            KafkaMessageRouteRegistry routeRegistry
    ) {
        this.objectMapper = objectMapper;
        this.routeRegistry = routeRegistry;
    }

    OutboxMessage create(OrderCreatedEvent event) {
        var route = routeRegistry.routeFor(event.contract());
        var headers = new LinkedHashMap<String, String>();
        headers.put("eventId", event.eventId().toString());
        headers.put("eventType", event.eventType());
        headers.put("schemaVersion", Integer.toString(event.schemaVersion()));
        headers.put("correlationId", event.correlationId().toString());
        headers.put("occurredAt", event.occurredAt().toString());

        return new OutboxMessage(
                event.eventId(),
                event.orderId(),
                AGGREGATE_TYPE,
                event.contract(),
                SOURCE,
                route.destinationTopic(),
                event.orderId().toString(),
                event.correlationId(),
                null,
                headers,
                serialize(event),
                event.occurredAt()
        );
    }

    private String serialize(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize OrderCreated outbox message", exception);
        }
    }
}
