package com.market.order.infrastructure.messaging;

import java.util.Objects;
import java.util.UUID;

record ClaimedOutboxMessage(
        OutboxMessage message,
        int attempt,
        UUID leaseId
) {

    ClaimedOutboxMessage {
        Objects.requireNonNull(message, "Outbox message must not be null");
        Objects.requireNonNull(leaseId, "Lease id must not be null");

        if (attempt <= 0) {
            throw new IllegalArgumentException("Publishing attempt must be positive");
        }
    }
}
