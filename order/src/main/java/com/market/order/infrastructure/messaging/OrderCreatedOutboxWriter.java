package com.market.order.infrastructure.messaging;

import com.market.order.application.OrderCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderCreatedOutboxWriter {

    private final OrderCreatedOutboxMessageFactory factory;
    private final OutboxMessageRepository repository;

    OrderCreatedOutboxWriter(
            OrderCreatedOutboxMessageFactory factory,
            OutboxMessageRepository repository
    ) {
        this.factory = factory;
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderCreatedEvent event) {
        var message = factory.create(event);
        repository.append(message);
    }
}
