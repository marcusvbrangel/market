package com.market.order.interfaces.rest;

import com.market.order.application.CreateOrderService;
import com.market.order.application.OrderQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;
    private final CreateOrderService createOrderService;

    public OrderController(
            OrderQueryService orderQueryService,
            CreateOrderService createOrderService
    ) {
        this.orderQueryService = orderQueryService;
        this.createOrderService = createOrderService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> findById(
            @PathVariable @NotNull UUID orderId
    ) {
        return orderQueryService.findById(orderId)
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request
    ) {
        var itemCommands = request.items().stream()
                .map(item -> new CreateOrderService.ItemCommand(
                        item.productId(), item.quantity()
                ))
                .toList();
        var order = createOrderService.create(request.customerId(), itemCommands);
        var location = URI.create("/api/v1/orders/" + order.id());
        return ResponseEntity.created(location).body(CreateOrderResponse.from(order));
    }
}
