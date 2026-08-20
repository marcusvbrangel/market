package com.market.order.application.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEnvelopeTest {

    @Test
    void shouldSerializeTheCommonEnvelopeContract() throws Exception {
        var messageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var correlationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var causationId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        var orderId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var occurredAt = Instant.parse("2026-08-20T20:15:30.123456Z");
        var envelope = new MessageEnvelope<>(
                messageId,
                "ReserveInventory",
                1,
                occurredAt,
                "order",
                correlationId,
                causationId,
                orderId,
                new SamplePayload("value")
        );

        var json = JsonMapper.shared().readTree(JsonMapper.shared().writeValueAsString(envelope));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "messageId",
                "messageType",
                "schemaVersion",
                "occurredAt",
                "source",
                "correlationId",
                "causationId",
                "orderId",
                "payload"
        );
        assertThat(json.get("messageId").asText()).isEqualTo(messageId.toString());
        assertThat(json.get("messageType").asText()).isEqualTo("ReserveInventory");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("occurredAt").asText()).isEqualTo(occurredAt.toString());
        assertThat(json.get("source").asText()).isEqualTo("order");
        assertThat(json.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(json.get("causationId").asText()).isEqualTo(causationId.toString());
        assertThat(json.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(json.get("payload").get("name").asText()).isEqualTo("value");
    }

    @Test
    void shouldRejectAnInvalidEnvelopeContract() {
        var id = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-20T20:15:30Z");

        assertThatThrownBy(() -> new MessageEnvelope<>(
                id,
                " ",
                1,
                occurredAt,
                "order",
                id,
                null,
                id,
                new SamplePayload("value")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message type must not be blank");

        assertThatThrownBy(() -> new MessageEnvelope<>(
                id,
                "ReserveInventory",
                0,
                occurredAt,
                "Order Service",
                id,
                null,
                id,
                new SamplePayload("value")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Schema version must be positive");

        assertThatThrownBy(() -> new MessageEnvelope<>(
                id,
                "ReserveInventory",
                1,
                occurredAt,
                "Order Service",
                id,
                null,
                id,
                new SamplePayload("value")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message source has an invalid format");
    }

    private record SamplePayload(String name) {
    }
}
