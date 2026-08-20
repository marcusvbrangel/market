ALTER TABLE orders
    ALTER COLUMN total_amount DROP NOT NULL,
    ALTER COLUMN currency DROP NOT NULL;

ALTER TABLE order_items
    ALTER COLUMN product_name DROP NOT NULL,
    ALTER COLUMN unit_price DROP NOT NULL,
    ALTER COLUMN subtotal DROP NOT NULL;

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_pending
    ON outbox_events (created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_id, occurred_at);
