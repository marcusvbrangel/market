package com.market.order.infrastructure.messaging;

import com.market.order.application.OrderCreatedEvent;
import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCreatedOutboxMessageFactoryTest {

    @Test
    void shouldKeepTheLegacyOrderCreatedPayloadAndHeadersUnchanged() throws Exception {
        var eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var orderId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var customerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        var productId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var occurredAt = Instant.parse("2026-08-20T20:15:30.123456Z");
        var event = new OrderCreatedEvent(
                eventId,
                orderId,
                customerId,
                List.of(new OrderCreatedEvent.Item(productId, 2)),
                occurredAt
        );
        var factory = factory("order-created-override.v1");

        var message = factory.create(event);
        var payload = JsonMapper.shared().readTree(message.payload());

        assertThat(message.destinationTopic()).isEqualTo("order-created-override.v1");
        assertThat(message.partitionKey()).isEqualTo(orderId.toString());
        assertThat(message.contract()).isEqualTo(
                new MessageContract(MessageCategory.EVENT, "OrderCreated", 1)
        );
        assertThat(message.source()).isEqualTo("order");
        assertThat(message.correlationId()).isEqualTo(orderId);
        assertThat(message.causationId()).isNull();
        assertThat(message.headers()).containsExactly(
                org.assertj.core.data.MapEntry.entry("eventId", eventId.toString()),
                org.assertj.core.data.MapEntry.entry("eventType", "OrderCreated"),
                org.assertj.core.data.MapEntry.entry("schemaVersion", "1"),
                org.assertj.core.data.MapEntry.entry("correlationId", orderId.toString()),
                org.assertj.core.data.MapEntry.entry("occurredAt", occurredAt.toString())
        );
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "schemaVersion",
                "correlationId",
                "orderId",
                "customerId",
                "items",
                "occurredAt"
        );
        assertThat(payload.has("messageId")).isFalse();
        assertThat(payload.has("source")).isFalse();
        assertThat(payload.has("causationId")).isFalse();
        assertThat(payload.has("payload")).isFalse();
        assertThat(payload.get("items").get(0).propertyNames())
                .containsExactlyInAnyOrder("productId", "quantity");
    }

    @Test
    void shouldRejectAContractWithoutAnExplicitRoute() {
        var registry = new KafkaMessageRouteRegistry(
                new KafkaTopicProperties("market.order.events.created.v1")
        );
        var unknownContract = new MessageContract(MessageCategory.COMMAND, "UnknownCommand", 1);

        assertThatThrownBy(() -> registry.routeFor(unknownContract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No Kafka route configured");
    }

    private OrderCreatedOutboxMessageFactory factory(String topic) {
        var registry = new KafkaMessageRouteRegistry(new KafkaTopicProperties(topic));
        return new OrderCreatedOutboxMessageFactory(JsonMapper.shared(), registry);
    }
}
