package com.market.order.infrastructure.messaging;

import com.market.order.application.CreateOrderService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "market.outbox.publisher.fixed-delay=1h"
})
@Testcontainers
class OutboxKafkaIntegrationTests {

    private static final String TOPIC = "market.order.events.created.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("1234");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private CreateOrderService createOrderService;

    @Autowired
    private TransactionalOutboxPublisher publisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldPublishOrderCreatedAndMarkOutboxAsPublished() {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var order = createOrderService.create(
                customerId,
                List.of(new CreateOrderService.ItemCommand(productId, 2))
        );

        publisher.publishBatch();

        assertThat(jdbcClient.sql("""
                        SELECT status FROM outbox_events WHERE aggregate_id = :orderId
                        """)
                .param("orderId", order.id())
                .query(String.class)
                .single()).isEqualTo("PUBLISHED");
        assertThat(jdbcClient.sql("""
                        SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = :orderId
                        """)
                .param("orderId", order.id())
                .query(Boolean.class)
                .single()).isTrue();

        try (var consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo(order.id().toString());
                assertThat(record.value())
                        .contains(order.id().toString(), customerId.toString(), productId.toString())
                        .doesNotContain("productName", "unitPrice", "subtotal");
                assertThat(record.headers().lastHeader("eventType").value())
                        .asString().isEqualTo("OrderCreated");
                assertThat(record.headers().lastHeader("schemaVersion").value())
                        .asString().isEqualTo("1");
                assertThat(record.headers().lastHeader("correlationId").value())
                        .asString().isEqualTo(order.id().toString());
            });
        }
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "order-outbox-integration-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ));
    }
}
