package com.market.order.infrastructure.messaging;

import com.market.order.application.CreateOrderService;
import com.market.order.application.messaging.MessageCategory;
import com.market.order.application.messaging.MessageContract;
import com.market.order.application.messaging.MessageEnvelope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "market.outbox.publisher.enabled=false")
@Testcontainers
class OutboxKafkaIntegrationTests {

    private static final String TOPIC = "market.order.events.created.v1";
    private static final String ROUTING_TEST_TOPIC = "market.test.commands.routing.v1";

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

    private TransactionalOutboxPublisher publisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxPublisherProperties publisherProperties;

    @BeforeEach
    void createPublisherWithoutStartingTheScheduler() {
        publisher = new TransactionalOutboxPublisher(
                repository,
                kafkaTemplate,
                publisherProperties
        );
    }

    @Test
    void shouldPublishOrderCreatedAndMarkOutboxAsPublished() {
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var idempotencyKey = "outbox-kafka-" + UUID.randomUUID();
        var result = createOrderService.create(
                idempotencyKey,
                customerId,
                List.of(new CreateOrderService.ItemCommand(productId, 2))
        );
        var orderId = result.orderId();

        var replayedResult = createOrderService.create(
                idempotencyKey,
                customerId,
                List.of(new CreateOrderService.ItemCommand(productId, 2))
        );

        assertThat(replayedResult.replayed()).isTrue();
        assertThat(replayedResult.orderId()).isEqualTo(orderId);

        publisher.publishBatch();

        assertThat(jdbcClient.sql("""
                        SELECT status FROM outbox_messages WHERE aggregate_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PUBLISHED");
        assertThat(jdbcClient.sql("""
                        SELECT published_at IS NOT NULL FROM outbox_messages WHERE aggregate_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Boolean.class)
                .single()).isTrue();

        try (var consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));
            var records = consume(TOPIC, consumer);
            assertThat(records).hasSize(1);
            assertThat(records).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo(orderId.toString());
                var payload = objectMapper.readTree(record.value());
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
                assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
                assertThat(payload.get("customerId").asText()).isEqualTo(customerId.toString());
                assertThat(payload.get("items").get(0).get("productId").asText())
                        .isEqualTo(productId.toString());
                assertThat(payload.get("items").get(0).propertyNames())
                        .containsExactlyInAnyOrder("productId", "quantity");
                assertThat(headers(record)).isEqualTo(Map.of(
                        "eventId", payload.get("eventId").asText(),
                        "eventType", "OrderCreated",
                        "schemaVersion", "1",
                        "correlationId", orderId.toString(),
                        "occurredAt", payload.get("occurredAt").asText()
                ));
            });
        }
    }

    @Test
    void shouldPublishACommonEnvelopeToItsPersistedDestination() throws Exception {
        var messageId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var causationId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-20T20:15:30.123456Z");
        var envelope = new MessageEnvelope<>(
                messageId,
                "RoutingProbe",
                1,
                occurredAt,
                "order",
                correlationId,
                causationId,
                orderId,
                Map.of("probe", "persisted-route")
        );
        var payload = objectMapper.writeValueAsString(envelope);
        var persistedHeaders = new LinkedHashMap<String, String>();
        persistedHeaders.put("messageId", messageId.toString());
        persistedHeaders.put("messageType", "RoutingProbe");
        persistedHeaders.put("test-marker", "persisted-verbatim");
        repository.append(new OutboxMessage(
                messageId,
                orderId,
                "ORDER",
                new MessageContract(MessageCategory.COMMAND, "RoutingProbe", 1),
                "order",
                ROUTING_TEST_TOPIC,
                "routing-key-" + orderId,
                correlationId,
                causationId,
                persistedHeaders,
                payload,
                occurredAt
        ));

        publisher.publishBatch();

        try (var consumer = consumer()) {
            var records = consume(ROUTING_TEST_TOPIC, consumer);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo("routing-key-" + orderId);
                assertThat(record.value()).isEqualTo(payload);
                assertThat(headers(record)).isEqualTo(persistedHeaders);
            });
        }
    }

    private List<ConsumerRecord<String, String>> consume(
            String topic,
            KafkaConsumer<String, String> consumer
    ) {
        consumer.subscribe(List.of(topic));
        var records = new ArrayList<ConsumerRecord<String, String>>();
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (records.isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).records(topic).forEach(records::add);
        }

        if (!records.isEmpty()) {
            consumer.poll(Duration.ofMillis(500)).records(topic).forEach(records::add);
        }

        return records;
    }

    private Map<String, String> headers(ConsumerRecord<String, String> record) {
        var headers = new LinkedHashMap<String, String>();

        record.headers().forEach(header -> headers.put(
                header.key(),
                new String(header.value(), StandardCharsets.UTF_8)
        ));

        return headers;
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
