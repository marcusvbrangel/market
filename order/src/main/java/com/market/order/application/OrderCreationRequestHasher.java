package com.market.order.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class OrderCreationRequestHasher {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final short FINGERPRINT_VERSION = 1;
    private static final String CANONICAL_FORMAT_VERSION = "order-creation-v" + FINGERPRINT_VERSION;

    public OrderCreationRequestFingerprint hash(
            UUID customerId,
            List<CreateOrderService.ItemCommand> requestedItems
    ) {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(requestedItems, "Requested items must not be null");

        var sortedItems = new ArrayList<>(requestedItems);
        sortedItems.sort(Comparator.comparing(item -> item.productId().toString()));

        var canonicalRequest = new StringBuilder();
        canonicalRequest.append(CANONICAL_FORMAT_VERSION);
        canonicalRequest.append('\n');
        canonicalRequest.append(customerId);
        canonicalRequest.append('\n');

        for (var item : sortedItems) {
            canonicalRequest.append(item.productId());
            canonicalRequest.append(':');
            canonicalRequest.append(item.quantity());
            canonicalRequest.append('\n');
        }

        var requestBytes = canonicalRequest.toString().getBytes(StandardCharsets.UTF_8);
        var digest = createDigest();
        var hashBytes = digest.digest(requestBytes);
        var hash = HexFormat.of().formatHex(hashBytes);
        return new OrderCreationRequestFingerprint(FINGERPRINT_VERSION, hash);
    }

    private MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", exception);
        }
    }
}
