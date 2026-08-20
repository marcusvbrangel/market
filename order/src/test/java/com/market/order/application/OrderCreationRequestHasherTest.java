package com.market.order.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreationRequestHasherTest {

    @Test
    void shouldKeepCanonicalHashCompatibleWithVersionOne() {
        var hasher = new OrderCreationRequestHasher();
        var customerId = UUID.fromString("0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a");
        var firstProductId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var secondProductId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var items = List.of(
                new CreateOrderService.ItemCommand(secondProductId, 2),
                new CreateOrderService.ItemCommand(firstProductId, 1)
        );

        var fingerprint = hasher.hash(customerId, items);

        assertThat(fingerprint.version()).isEqualTo((short) 1);
        assertThat(fingerprint.hash())
                .isEqualTo("5798d1b87558114d39b20bd51a3ff74cbb3be8e32cca09b2467dc487f2147e96");
    }

    @Test
    void shouldIgnoreItemOrderWhenCalculatingHash() {
        var hasher = new OrderCreationRequestHasher();
        var customerId = UUID.randomUUID();
        var firstProductId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var secondProductId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var originalItems = List.of(
                new CreateOrderService.ItemCommand(firstProductId, 1),
                new CreateOrderService.ItemCommand(secondProductId, 2)
        );
        var reorderedItems = List.of(
                new CreateOrderService.ItemCommand(secondProductId, 2),
                new CreateOrderService.ItemCommand(firstProductId, 1)
        );

        var originalFingerprint = hasher.hash(customerId, originalItems);
        var reorderedFingerprint = hasher.hash(customerId, reorderedItems);

        assertThat(originalFingerprint).isEqualTo(reorderedFingerprint);
        assertThat(originalFingerprint.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldChangeHashWhenQuantityChanges() {
        var hasher = new OrderCreationRequestHasher();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var originalItems = List.of(new CreateOrderService.ItemCommand(productId, 1));
        var changedItems = List.of(new CreateOrderService.ItemCommand(productId, 2));

        var originalFingerprint = hasher.hash(customerId, originalItems);
        var changedFingerprint = hasher.hash(customerId, changedItems);

        assertThat(originalFingerprint).isNotEqualTo(changedFingerprint);
    }
}
