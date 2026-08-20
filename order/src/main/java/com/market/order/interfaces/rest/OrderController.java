package com.market.order.interfaces.rest;

import com.market.order.application.CreateOrderService;
import com.market.order.application.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Pedidos")
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
    @Operation(
            summary = "Consultar pedido por identificador",
            description = "Retorna o pedido persistido, incluindo seus itens e o estado atual."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Pedido encontrado",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Identificador inválido", content = @Content),
            @ApiResponse(responseCode = "404", description = "Pedido não encontrado", content = @Content)
    })
    public ResponseEntity<OrderResponse> findById(
            @Parameter(description = "Identificador único do pedido", required = true)
            @PathVariable @NotNull UUID orderId
    ) {
        return orderQueryService.findById(orderId)
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(
            summary = "Criar pedido",
            description = "Cria um pedido PENDING e grava o evento OrderCreated na Transactional Outbox."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Pedido criado",
                    headers = @Header(
                            name = "Location",
                            description = "URI para consulta do pedido criado",
                            schema = @Schema(type = "string", example = "/api/v1/orders/e309bd65-d3e7-486f-b115-42e5d8ec5f08")
                    ),
                    content = @Content(schema = @Schema(implementation = CreateOrderResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Corpo da requisição inválido", content = @Content)
    })
    public ResponseEntity<CreateOrderResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Cliente e itens solicitados. Nome e preço dos produtos não são aceitos."
            )
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
