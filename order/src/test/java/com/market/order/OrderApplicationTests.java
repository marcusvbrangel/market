package com.market.order;

import com.market.order.application.CreateOrderService;
import com.market.order.application.OrderQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "market.outbox.publisher.enabled=false")
@AutoConfigureMockMvc
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

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void shouldApplyMigrationsAndLoadPersistedOrder() {
		var order = service.findById(SAMPLE_ORDER_ID);

		assertThat(order).isPresent().get().satisfies(found -> {
			assertThat(found.orderNumber()).isEqualTo("ORD-2026-000001");
			assertThat(found.items()).hasSize(5);
			assertThat(found.totalAmount()).isEqualByComparingTo(new BigDecimal("6549.40"));
		});
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
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
		var result = createOrderService.create(
				"application-test-" + UUID.randomUUID(),
				UUID.randomUUID(),
				java.util.List.of(new CreateOrderService.ItemCommand(productId, 3))
		);
		var orderId = result.orderId();

		assertThat(service.findById(orderId)).isPresent().get().satisfies(found -> {
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
				.param("orderId", orderId)
				.query(Long.class)
				.single()).isEqualTo(1L);
		assertThat(jdbcClient.sql("""
				SELECT payload::text FROM outbox_events WHERE aggregate_id = :orderId
				""")
				.param("orderId", orderId)
				.query(String.class)
				.single())
				.contains(productId.toString())
				.doesNotContain("productName", "unitPrice", "subtotal");
	}

	@Test
	void shouldReplaySameCreationWithoutDuplicatingOrderOrOutbox() throws Exception {
		var customerId = UUID.randomUUID();
		var productId = UUID.randomUUID();
		var idempotencyKey = "checkout-replay-" + UUID.randomUUID();
		var requestBody = creationRequest(customerId, productId, 2);

		var firstResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(requestBody))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andReturn();

		var secondResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(requestBody))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		var firstBody = firstResponse.getResponse().getContentAsString();
		var secondBody = secondResponse.getResponse().getContentAsString();
		var orderId = UUID.fromString(objectMapper.readTree(firstBody).get("id").asText());

		assertThat(secondBody).isEqualTo(firstBody);
		assertThat(secondResponse.getResponse().getHeader("Location"))
				.isEqualTo(firstResponse.getResponse().getHeader("Location"));
		assertThat(countIdempotencyRecords(customerId, idempotencyKey)).isEqualTo(1L);
		assertThat(countOrders(orderId)).isEqualTo(1L);
		assertThat(countOrderItems(orderId)).isEqualTo(1L);
		assertThat(countOutboxEvents(orderId)).isEqualTo(1L);
	}

	@Test
	void shouldReturnConflictWhenSameKeyHasDifferentPayload() throws Exception {
		var customerId = UUID.randomUUID();
		var productId = UUID.randomUUID();
		var idempotencyKey = "checkout-conflict-" + UUID.randomUUID();

		mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(creationRequest(customerId, productId, 1)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(creationRequest(customerId, productId, 2)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		var orderId = idempotentOrderId(customerId, idempotencyKey);
		assertThat(countIdempotencyRecords(customerId, idempotencyKey)).isEqualTo(1L);
		assertThat(countOrders(orderId)).isEqualTo(1L);
		assertThat(countOutboxEvents(orderId)).isEqualTo(1L);
	}

	@Test
	void shouldReplayWhenOnlyItemOrderChanges() throws Exception {
		var customerId = UUID.randomUUID();
		var firstProductId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		var secondProductId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		var idempotencyKey = "checkout-reordered-" + UUID.randomUUID();
		var originalRequest = creationRequestWithTwoProducts(
				customerId,
				firstProductId,
				1,
				secondProductId,
				2
		);
		var reorderedRequest = creationRequestWithTwoProducts(
				customerId,
				secondProductId,
				2,
				firstProductId,
				1
		);

		var firstResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(originalRequest))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andReturn();

		var replayResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(reorderedRequest))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replayResponse.getResponse().getContentAsString())
				.isEqualTo(firstResponse.getResponse().getContentAsString());
	}

	@Test
	void shouldAllowSameIdempotencyKeyForDifferentCustomers() throws Exception {
		var firstCustomerId = UUID.randomUUID();
		var secondCustomerId = UUID.randomUUID();
		var productId = UUID.randomUUID();
		var idempotencyKey = "checkout-customer-scope-" + UUID.randomUUID();

		var firstResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(creationRequest(firstCustomerId, productId, 1)))
				.andExpect(status().isCreated())
				.andReturn();

		var secondResponse = mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(creationRequest(secondCustomerId, productId, 1)))
				.andExpect(status().isCreated())
				.andReturn();

		var firstOrderId = objectMapper.readTree(firstResponse.getResponse().getContentAsString())
				.get("id")
				.asText();
		var secondOrderId = objectMapper.readTree(secondResponse.getResponse().getContentAsString())
				.get("id")
				.asText();

		assertThat(firstOrderId).isNotEqualTo(secondOrderId);
		assertThat(countIdempotencyRecords(firstCustomerId, idempotencyKey)).isEqualTo(1L);
		assertThat(countIdempotencyRecords(secondCustomerId, idempotencyKey)).isEqualTo(1L);
	}

	@Test
	void shouldRejectDuplicateProductWithoutConsumingIdempotencyKey() throws Exception {
		var customerId = UUID.randomUUID();
		var productId = UUID.randomUUID();
		var idempotencyKey = "checkout-duplicate-" + UUID.randomUUID();
		var requestBody = """
				{
				  "customerId": "%s",
				  "items": [
				    {"productId": "%s", "quantity": 1},
				    {"productId": "%s", "quantity": 2}
				  ]
				}
				""".formatted(customerId, productId, productId);

		mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", idempotencyKey)
						.contentType("application/json")
						.content(requestBody))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT"));

		assertThat(countIdempotencyRecords(customerId, idempotencyKey)).isZero();
	}

	@Test
	void shouldRejectInvalidIdempotencyKey() throws Exception {
		var customerId = UUID.randomUUID();
		var productId = UUID.randomUUID();

		mockMvc.perform(post("/api/v1/orders")
						.header("Idempotency-Key", "invalid key with spaces")
						.contentType("application/json")
						.content(creationRequest(customerId, productId, 1)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
	}

	@Test
	void shouldReleaseIdempotencyKeyWhenCreationTransactionRollsBack() {
		var customerId = UUID.randomUUID();
		var productId = UUID.randomUUID();
		var idempotencyKey = "checkout-rollback-" + UUID.randomUUID();
		var items = java.util.List.of(new CreateOrderService.ItemCommand(productId, 1));
		var transactionTemplate = new TransactionTemplate(transactionManager);

		var rolledBackResult = transactionTemplate.execute(status -> {
			var result = createOrderService.create(
					idempotencyKey,
					customerId,
					items
			);
			status.setRollbackOnly();
			return result;
		});

		assertThat(rolledBackResult).isNotNull();
		assertThat(countIdempotencyRecords(customerId, idempotencyKey)).isZero();
		assertThat(countOrders(rolledBackResult.orderId())).isZero();
		assertThat(countOrderItems(rolledBackResult.orderId())).isZero();
		assertThat(countOutboxEvents(rolledBackResult.orderId())).isZero();

		var retryResult = createOrderService.create(
				idempotencyKey,
				customerId,
				items
		);

		assertThat(retryResult.replayed()).isFalse();
		assertThat(retryResult.orderId()).isNotEqualTo(rolledBackResult.orderId());
		assertThat(countIdempotencyRecords(customerId, idempotencyKey)).isEqualTo(1L);
		assertThat(countOrders(retryResult.orderId())).isEqualTo(1L);
		assertThat(countOrderItems(retryResult.orderId())).isEqualTo(1L);
		assertThat(countOutboxEvents(retryResult.orderId())).isEqualTo(1L);
	}

	@Test
	void shouldEnforcePricingPresenceInPostgres() {
		var orderId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcClient.sql("""
						INSERT INTO orders (
						    id, order_number, customer_id, status,
						    total_amount, currency, rejection_reason,
						    created_at, updated_at, version
						) VALUES (
						    :id, :orderNumber, :customerId, 'PENDING',
						    10.00, NULL, NULL,
						    now(), now(), 0
						)
						""")
				.param("id", orderId)
				.param("orderNumber", "ORD-CONSTRAINT-" + orderId.toString().substring(0, 8))
				.param("customerId", UUID.randomUUID())
				.update())
				.rootCause()
				.hasMessageContaining("ck_orders_pricing_presence");
	}

	@Test
	void shouldRequirePriceForConfirmedOrderInPostgres() {
		var orderId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcClient.sql("""
						INSERT INTO orders (
						    id, order_number, customer_id, status,
						    total_amount, currency, rejection_reason,
						    created_at, updated_at, version
						) VALUES (
						    :id, :orderNumber, :customerId, 'CONFIRMED',
						    NULL, NULL, NULL,
						    now(), now(), 0
						)
						""")
				.param("id", orderId)
				.param("orderNumber", "ORD-CONFIRMED-" + orderId.toString().substring(0, 8))
				.param("customerId", UUID.randomUUID())
				.update())
				.rootCause()
				.hasMessageContaining("ck_orders_confirmed_priced");
	}

	@Test
	void shouldRejectCurrencyOtherThanBrlInPostgres() {
		var orderId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcClient.sql("""
						INSERT INTO orders (
						    id, order_number, customer_id, status,
						    total_amount, currency, rejection_reason,
						    created_at, updated_at, version
						) VALUES (
						    :id, :orderNumber, :customerId, 'PENDING',
						    10.00, 'USD', NULL,
						    now(), now(), 0
						)
						""")
				.param("id", orderId)
				.param("orderNumber", "ORD-CURRENCY-" + orderId.toString().substring(0, 8))
				.param("customerId", UUID.randomUUID())
				.update())
				.rootCause()
				.hasMessageContaining("ck_orders_currency_brl");
	}

	@Test
	void shouldRejectPartiallyPricedItemInPostgres() {
		var productId = UUID.randomUUID();
		var result = createOrderService.create(
				"constraint-item-price-" + UUID.randomUUID(),
				UUID.randomUUID(),
				java.util.List.of(new CreateOrderService.ItemCommand(productId, 1))
		);

		assertThatThrownBy(() -> jdbcClient.sql("""
						UPDATE order_items
						SET product_name = 'Partially priced product'
						WHERE order_id = :orderId
						  AND product_id = :productId
						""")
				.param("orderId", result.orderId())
				.param("productId", productId)
				.update())
				.rootCause()
				.hasMessageContaining("ck_order_items_pricing_presence");
	}

	@Test
	void shouldEnforceUniqueProductPerOrderInPostgres() {
		var productId = UUID.randomUUID();
		var result = createOrderService.create(
				"constraint-product-" + UUID.randomUUID(),
				UUID.randomUUID(),
				java.util.List.of(new CreateOrderService.ItemCommand(productId, 1))
		);

		assertThatThrownBy(() -> jdbcClient.sql("""
						INSERT INTO order_items (
						    id, order_id, product_id, product_name,
						    quantity, unit_price, subtotal, position
						) VALUES (
						    :id, :orderId, :productId, NULL,
						    1, NULL, NULL, 1
						)
						""")
				.param("id", UUID.randomUUID())
				.param("orderId", result.orderId())
				.param("productId", productId)
				.update())
				.rootCause()
				.hasMessageContaining("uq_order_items_product");
	}

	@Test
	void shouldEnforceIdempotencyKeyFormatInPostgres() {
		var orderId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcClient.sql("""
						INSERT INTO api_idempotency (
						    customer_id, idempotency_key,
						    request_hash_version, request_hash, order_id,
						    response_order_number, response_order_status,
						    response_created_at, created_at
						) VALUES (
						    :customerId, 'invalid key',
						    1, :requestHash, :orderId,
						    :orderNumber, 'PENDING',
						    now(), now()
						)
						""")
				.param("customerId", UUID.randomUUID())
				.param("requestHash", "a".repeat(64))
				.param("orderId", orderId)
				.param("orderNumber", "ORD-IDEMPOTENCY-" + orderId.toString().substring(0, 8))
				.update())
				.rootCause()
				.hasMessageContaining("ck_api_idempotency_key");
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

	@Test
	void shouldRejectUnsupportedRequestFieldOverHttp() throws Exception {
		mockMvc.perform(post("/api/v1/orders")
					.header("Idempotency-Key", "checkout-http-unknown-field")
					.contentType("application/json")
					.content("""
							{
							  "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
							  "items": [{
							    "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
							    "quantity": 2,
							    "unitPrice": 10.00
							  }]
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
	}

	@Test
	void shouldExposeOpenApiContractInJsonAndYaml() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
				.andExpect(jsonPath("$.info.title").value("Market Order API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['201']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['400'].content['application/problem+json'].schema['$ref']")
						.value("#/components/schemas/ApiProblemResponse"))
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['409'].content['application/problem+json'].schema['$ref']")
						.value("#/components/schemas/ApiProblemResponse"))
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.parameters[0].name")
						.value("Idempotency-Key"))
				.andExpect(jsonPath("$.paths['/api/v1/orders'].post.parameters[0].required")
						.value(true))
				.andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].get.responses['200']").exists())
				.andExpect(jsonPath("$.components.schemas.CreateOrderRequest").exists())
				.andExpect(jsonPath("$.components.schemas.ApiProblemResponse").exists())
				.andExpect(jsonPath("$.components.schemas.OrderResponse").exists());

		mockMvc.perform(get("/v3/api-docs.yaml"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("title: Market Order API")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/orders:")));
	}

	@Test
	void shouldExposeSwaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/swagger-ui/index.html"));
	}

	private String creationRequest(UUID customerId, UUID productId, int quantity) {
		return """
				{
				  "customerId": "%s",
				  "items": [{
				    "productId": "%s",
				    "quantity": %d
				  }]
				}
				""".formatted(customerId, productId, quantity);
	}

	private String creationRequestWithTwoProducts(
			UUID customerId,
			UUID firstProductId,
			int firstQuantity,
			UUID secondProductId,
			int secondQuantity
	) {
		return """
				{
				  "customerId": "%s",
				  "items": [
				    {"productId": "%s", "quantity": %d},
				    {"productId": "%s", "quantity": %d}
				  ]
				}
				""".formatted(
				customerId,
				firstProductId,
				firstQuantity,
				secondProductId,
				secondQuantity
		);
	}

	private long countIdempotencyRecords(UUID customerId, String idempotencyKey) {
		return jdbcClient.sql("""
						SELECT count(*)
						FROM api_idempotency
						WHERE customer_id = :customerId
						  AND idempotency_key = :idempotencyKey
						""")
				.param("customerId", customerId)
				.param("idempotencyKey", idempotencyKey)
				.query(Long.class)
				.single();
	}

	private UUID idempotentOrderId(UUID customerId, String idempotencyKey) {
		return jdbcClient.sql("""
						SELECT order_id
						FROM api_idempotency
						WHERE customer_id = :customerId
						  AND idempotency_key = :idempotencyKey
						""")
				.param("customerId", customerId)
				.param("idempotencyKey", idempotencyKey)
				.query(UUID.class)
				.single();
	}

	private long countOrders(UUID orderId) {
		return jdbcClient.sql("SELECT count(*) FROM orders WHERE id = :orderId")
				.param("orderId", orderId)
				.query(Long.class)
				.single();
	}

	private long countOrderItems(UUID orderId) {
		return jdbcClient.sql("SELECT count(*) FROM order_items WHERE order_id = :orderId")
				.param("orderId", orderId)
				.query(Long.class)
				.single();
	}

	private long countOutboxEvents(UUID orderId) {
		return jdbcClient.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :orderId")
				.param("orderId", orderId)
				.query(Long.class)
				.single();
	}

}
