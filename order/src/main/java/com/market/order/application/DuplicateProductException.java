package com.market.order.application;

import java.util.UUID;

public class DuplicateProductException extends RuntimeException {

    public DuplicateProductException(UUID productId) {
        super("Product must appear only once in an order: " + productId);
    }
}
