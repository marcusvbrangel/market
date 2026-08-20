package com.market.order;

import com.market.order.application.CreateOrderService;
import com.market.order.application.OrderQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OrderApplicationTests {

	private static final UUID SAMPLE_ORDER_ID = UUID.fromString(
			"550e8400-e29b-41d4-a716-446655440000"
	);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
			.withDatabaseName("order_db")
			.withUsername("order_user")
			.withPassword("1234");

	@Autowired
	private OrderQueryService service;

	@Autowired
	private CreateOrderService createOrderService;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private Flyway flyway;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void shouldApplyMigrationsAndLoadPersistedOrder() {
		var order = service.findById(SAMPLE_ORDER_ID);

		assertThat(order).isPresent().get().satisfies(found -> {
			assertThat(found.orderNumber()).isEqualTo("ORD-2026-000001");
			assertThat(found.items()).hasSize(5);
			assertThat(found.totalAmount()).isEqualByComparingTo(new BigDecimal("6549.40"));
		});
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
		assertThat(jdbcClient.sql("SELECT count(*) FROM orders WHERE id = :id")
				.param("id", SAMPLE_ORDER_ID)
				.query(Long.class)
				.single()).isEqualTo(1L);
		assertThat(jdbcClient.sql("SELECT count(*) FROM order_items WHERE order_id = :orderId")
				.param("orderId", SAMPLE_ORDER_ID)
				.query(Long.class)
				.single()).isEqualTo(5L);
	}

	@Test
	void shouldPersistNewOrderAndOutboxEventInTheSameFlow() {
		var productId = UUID.randomUUID();
		var order = createOrderService.create(
				UUID.randomUUID(),
				java.util.List.of(new CreateOrderService.ItemCommand(productId, 3))
		);

		assertThat(service.findById(order.id())).isPresent().get().satisfies(found -> {
			assertThat(found.status().name()).isEqualTo("PENDING");
			assertThat(found.totalAmount()).isNull();
			assertThat(found.items()).singleElement().satisfies(item -> {
				assertThat(item.productId()).isEqualTo(productId);
				assertThat(item.quantity()).isEqualTo(3);
				assertThat(item.productName()).isNull();
				assertThat(item.unitPrice()).isNull();
			});
		});
		assertThat(jdbcClient.sql("""
				SELECT count(*) FROM outbox_events
				WHERE aggregate_id = :orderId AND status = 'PENDING'
				""")
				.param("orderId", order.id())
				.query(Long.class)
				.single()).isEqualTo(1L);
		assertThat(jdbcClient.sql("""
				SELECT payload::text FROM outbox_events WHERE aggregate_id = :orderId
				""")
				.param("orderId", order.id())
				.query(String.class)
				.single())
				.contains(productId.toString())
				.doesNotContain("productName", "unitPrice", "subtotal");
	}

	@Test
	void shouldReturnEmptyForUnknownOrder() {
		assertThat(service.findById(UUID.randomUUID())).isEmpty();
	}

	@Test
	void shouldRejectProductNameAndPriceInCreationContract() {
		assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
				.isTrue();
		assertThatThrownBy(() -> objectMapper.readValue("""
				{
				  "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
				  "items": [{
				    "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
				    "quantity": 1,
				    "productName": "Must not be accepted",
				    "unitPrice": 10.00
				  }]
				}
				""", com.market.order.interfaces.rest.CreateOrderRequest.class))
				.isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
	}

}
