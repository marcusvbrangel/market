package com.market.order.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

record OutboxEvent(
        UUID id,
        UUID aggregateId,
        String eventType,
        String payload,
        int attempts,
        Instant occurredAt
) {
}
