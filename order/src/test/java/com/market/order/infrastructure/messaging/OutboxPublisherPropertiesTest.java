package com.market.order.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPublisherPropertiesTest {

    @Test
    void shouldRequireTheLeaseToOutliveTheKafkaSendTimeout() {
        assertThatThrownBy(() -> new OutboxPublisherProperties(
                50,
                5,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                5_000
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outbox lease duration must exceed the complete Kafka send budget");
    }
}
