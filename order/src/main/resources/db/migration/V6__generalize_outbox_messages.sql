DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM outbox_events
        WHERE aggregate_type <> 'ORDER'
           OR event_type <> 'OrderCreated'
           OR jsonb_typeof(payload) <> 'object'
           OR payload ->> 'eventId' IS DISTINCT FROM id::text
           OR payload ->> 'orderId' IS DISTINCT FROM aggregate_id::text
           OR payload ->> 'occurredAt' IS NULL
           OR NOT (
                (
                    NOT (payload ? 'eventType')
                    AND NOT (payload ? 'schemaVersion')
                    AND NOT (payload ? 'correlationId')
                )
                OR
                (
                    payload ->> 'eventType' IS NOT DISTINCT FROM event_type
                    AND payload ->> 'schemaVersion' IS NOT DISTINCT FROM '1'
                    AND payload ->> 'correlationId' IS NOT DISTINCT FROM aggregate_id::text
                )
           )
    ) THEN
        RAISE EXCEPTION
            'V6 cannot route an unknown or inconsistent legacy outbox contract';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM outbox_events
        WHERE (payload ->> 'occurredAt')::timestamptz IS DISTINCT FROM occurred_at
    ) THEN
        RAISE EXCEPTION
            'V6 cannot route a legacy outbox row with inconsistent occurredAt';
    END IF;
END;
$$;

ALTER TABLE outbox_events
    RENAME TO outbox_messages;

ALTER TABLE outbox_messages
    RENAME COLUMN id TO message_id;

ALTER TABLE outbox_messages
    RENAME COLUMN event_type TO message_type;

ALTER TABLE outbox_messages
    ADD COLUMN message_category VARCHAR(16),
    ADD COLUMN schema_version SMALLINT,
    ADD COLUMN source VARCHAR(100),
    ADD COLUMN destination_topic VARCHAR(249),
    ADD COLUMN partition_key VARCHAR(255),
    ADD COLUMN correlation_id UUID,
    ADD COLUMN causation_id UUID,
    ADD COLUMN headers JSONB,
    ADD COLUMN lease_id UUID,
    ADD COLUMN lease_until TIMESTAMPTZ;

UPDATE outbox_messages
SET message_category = 'EVENT',
    schema_version = 1,
    source = 'order',
    destination_topic = 'market.order.events.created.v1',
    partition_key = aggregate_id::text,
    correlation_id = aggregate_id,
    causation_id = NULL,
    headers = jsonb_build_object(
        'eventId', payload ->> 'eventId',
        'eventType', message_type,
        'schemaVersion', '1',
        'correlationId', aggregate_id::text,
        'occurredAt', CASE
            WHEN date_trunc('second', occurred_at) = occurred_at
                THEN to_char(
                    occurred_at AT TIME ZONE 'UTC',
                    'YYYY-MM-DD"T"HH24:MI:SS"Z"'
                )
            WHEN EXTRACT(MICROSECONDS FROM occurred_at)::bigint % 1000 = 0
                THEN to_char(
                    occurred_at AT TIME ZONE 'UTC',
                    'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
                )
            ELSE to_char(
                occurred_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
            )
        END
    ),
    lease_id = CASE
        WHEN status = 'PROCESSING' THEN gen_random_uuid()
        ELSE NULL
    END,
    lease_until = CASE
        WHEN status = 'PROCESSING' THEN now()
        ELSE NULL
    END;

ALTER TABLE outbox_messages
    ALTER COLUMN payload TYPE TEXT USING payload::text;

ALTER TABLE outbox_messages
    ALTER COLUMN message_category SET NOT NULL,
    ALTER COLUMN schema_version SET NOT NULL,
    ALTER COLUMN source SET NOT NULL,
    ALTER COLUMN destination_topic SET NOT NULL,
    ALTER COLUMN partition_key SET NOT NULL,
    ALTER COLUMN correlation_id SET NOT NULL,
    ALTER COLUMN headers SET NOT NULL;

ALTER TABLE outbox_messages
    RENAME CONSTRAINT outbox_events_pkey TO pk_outbox_messages;

ALTER TABLE outbox_messages
    RENAME CONSTRAINT ck_outbox_events_status TO ck_outbox_messages_status;

ALTER TABLE outbox_messages
    RENAME CONSTRAINT ck_outbox_events_attempts TO ck_outbox_messages_attempts;

ALTER TABLE outbox_messages
    ADD CONSTRAINT ck_outbox_messages_category
        CHECK (message_category IN ('COMMAND', 'EVENT')),
    ADD CONSTRAINT ck_outbox_messages_schema_version
        CHECK (schema_version > 0),
    ADD CONSTRAINT ck_outbox_messages_aggregate_type
        CHECK (length(btrim(aggregate_type)) > 0),
    ADD CONSTRAINT ck_outbox_messages_type
        CHECK (length(btrim(message_type)) > 0),
    ADD CONSTRAINT ck_outbox_messages_source
        CHECK (source ~ '^[a-z][a-z0-9-]{0,99}$'),
    ADD CONSTRAINT ck_outbox_messages_destination
        CHECK (destination_topic ~ '^[A-Za-z0-9._-]{1,249}$'),
    ADD CONSTRAINT ck_outbox_messages_partition_key
        CHECK (length(btrim(partition_key)) > 0),
    ADD CONSTRAINT ck_outbox_messages_headers
        CHECK (
            jsonb_typeof(headers) = 'object'
            AND NOT jsonb_path_exists(
                headers,
                '$.keyvalue() ? (@.key like_regex "^\\s*$" || @.value.type() != "string")'
            )
        ),
    ADD CONSTRAINT ck_outbox_messages_payload
        CHECK (payload IS JSON OBJECT),
    ADD CONSTRAINT ck_outbox_messages_lease
        CHECK (
            (status = 'PROCESSING' AND lease_id IS NOT NULL AND lease_until IS NOT NULL)
            OR
            (status <> 'PROCESSING' AND lease_id IS NULL AND lease_until IS NULL)
        );

DROP INDEX idx_outbox_events_publishable;

ALTER INDEX idx_outbox_events_aggregate
    RENAME TO idx_outbox_messages_aggregate;

CREATE INDEX idx_outbox_messages_pending
    ON outbox_messages (COALESCE(next_attempt_at, created_at), created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_messages_processing_lease
    ON outbox_messages (lease_until, created_at)
    WHERE status = 'PROCESSING';
