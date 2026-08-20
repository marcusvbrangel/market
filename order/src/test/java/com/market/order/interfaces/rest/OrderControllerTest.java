package com.market.order.interfaces.rest;

import com.market.order.application.CreateOrderService;
import com.market.order.application.OrderQueryService;
import com.market.order.domain.Order;
import com.market.order.domain.OrderItem;
import com.market.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OrderControllerTest {

    private final OrderQueryService service = mock(OrderQueryService.class);
    private final CreateOrderService createOrderService = mock(CreateOrderService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new OrderController(service, createOrderService)).build();
    }

    @Test
    void shouldReturnPersistedOrderContract() throws Exception {
        var order = order();
        when(service.findById(order.id())).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.id().toString()))
                .andExpect(jsonPath("$.orderNumber").value("ORD-TEST-1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalAmount").value(20.00));
    }

    @Test
    void shouldReturnNotFoundWhenOrderDoesNotExist() throws Exception {
        var orderId = UUID.randomUUID();
        when(service.findById(orderId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnBadRequestForInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{orderId}", "invalid-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldCreateOrderWithoutProductNameOrPrice() throws Exception {
        var order = pendingOrder();
        when(createOrderService.create(anyUuid(), anyList())).thenReturn(order);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content("""
                                {
                                  "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
                                  "items": [
                                    {
                                      "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + order.id()))
                .andExpect(jsonPath("$.id").value(order.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void shouldRejectCreationWithoutItems() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content("""
                                {
                                  "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
                                  "items": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private Order order() {
        var item = new OrderItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Electronic product",
                2,
                new BigDecimal("10.00"),
                new BigDecimal("20.00")
        );
        return new Order(
                UUID.randomUUID(),
                "ORD-TEST-1",
                UUID.randomUUID(),
                OrderStatus.CONFIRMED,
                List.of(item),
                new BigDecimal("20.00"),
                "BRL",
                null,
                Instant.parse("2026-08-19T10:00:00Z"),
                Instant.parse("2026-08-19T10:01:00Z")
        );
    }

    private Order pendingOrder() {
        return Order.pending(
                UUID.randomUUID(),
                "ORD-20260819-TEST0001",
                UUID.fromString("0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a"),
                List.of(OrderItem.requested(
                        UUID.randomUUID(),
                        UUID.fromString("6c20b55a-2e09-4473-98a6-411f48a8bb23"),
                        2
                )),
                Instant.parse("2026-08-19T10:00:00Z")
        );
    }

    private UUID anyUuid() {
        return org.mockito.ArgumentMatchers.any(UUID.class);
    }

    private List<CreateOrderService.ItemCommand> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }
}
