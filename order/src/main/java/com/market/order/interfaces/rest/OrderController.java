package com.market.order.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final UUID SAMPLE_ORDER_ID = UUID.fromString(
            "550e8400-e29b-41d4-a716-446655440000"
    );

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> findById(
            @PathVariable @NotNull UUID orderId
    ) {
        if (!SAMPLE_ORDER_ID.equals(orderId)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(sampleOrder());
    }

    private OrderResponse sampleOrder() {
        return new OrderResponse(
                SAMPLE_ORDER_ID,
                "ORD-2026-000001",
                UUID.fromString("0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a"),
                OrderResponse.Status.CONFIRMED,
                List.of(
                        new OrderResponse.Item(
                                UUID.fromString("9c282be7-0f09-4c90-89ea-af3234d1f8ed"),
                                UUID.fromString("6c20b55a-2e09-4473-98a6-411f48a8bb23"),
                                "Smart TV 55 polegadas 4K",
                                1,
                                new BigDecimal("2499.90"),
                                new BigDecimal("2499.90")
                        ),
                        new OrderResponse.Item(
                                UUID.fromString("09066db0-cf7e-4411-8319-298651683a08"),
                                UUID.fromString("41b22397-7c78-43c1-b587-0dc243d76af9"),
                                "Soundbar com subwoofer Bluetooth",
                                1,
                                new BigDecimal("899.90"),
                                new BigDecimal("899.90")
                        ),
                        new OrderResponse.Item(
                                UUID.fromString("fabdf85a-0d0a-4d0d-a6a0-29fe2b6e7d36"),
                                UUID.fromString("5da9c24f-70b8-4a5d-90e2-f72b4d26ec44"),
                                "Kit alarme residencial inteligente",
                                1,
                                new BigDecimal("329.90"),
                                new BigDecimal("329.90")
                        ),
                        new OrderResponse.Item(
                                UUID.fromString("4f84ab27-71f8-42c1-b3bd-ac44a8af21c0"),
                                UUID.fromString("c18d10ba-2c75-45d1-9e2d-5694e10b4f2f"),
                                "Smartphone 5G 256 GB",
                                1,
                                new BigDecimal("1899.90"),
                                new BigDecimal("1899.90")
                        ),
                        new OrderResponse.Item(
                                UUID.fromString("d2350054-813e-422f-bb3d-7032143c1bd7"),
                                UUID.fromString("aba00ff4-ed82-4462-b1ae-fd6a02a90f37"),
                                "Câmera de segurança Wi-Fi Full HD",
                                2,
                                new BigDecimal("459.90"),
                                new BigDecimal("919.80")
                        )
                ),
                new BigDecimal("6549.40"),
                "BRL",
                null,
                Instant.parse("2026-08-19T18:30:00Z"),
                Instant.parse("2026-08-19T18:31:12Z")
        );
    }
}
