ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(1000);

DROP INDEX idx_outbox_events_pending;

CREATE INDEX idx_outbox_events_publishable
    ON outbox_events (COALESCE(next_attempt_at, created_at), created_at)
    WHERE status = 'PENDING';
