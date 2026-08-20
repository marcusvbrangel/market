ALTER TABLE orders
    ADD CONSTRAINT ck_orders_pricing_presence
        CHECK (
            (total_amount IS NULL AND currency IS NULL)
            OR (total_amount IS NOT NULL AND currency IS NOT NULL)
        ),
    ADD CONSTRAINT ck_orders_confirmed_priced
        CHECK (
            status <> 'CONFIRMED'
            OR (total_amount IS NOT NULL AND currency IS NOT NULL)
        ),
    ADD CONSTRAINT ck_orders_currency_brl
        CHECK (currency IS NULL OR currency = 'BRL'),
    ADD CONSTRAINT uq_orders_id_customer
        UNIQUE (id, customer_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_items
        GROUP BY order_id, product_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V5 cannot enforce unique products because duplicate order items already exist';
    END IF;
END;
$$;

ALTER TABLE order_items
    ADD CONSTRAINT ck_order_items_pricing_presence
        CHECK (
            (product_name IS NULL AND unit_price IS NULL AND subtotal IS NULL)
            OR (product_name IS NOT NULL AND unit_price IS NOT NULL AND subtotal IS NOT NULL)
        ),
    ADD CONSTRAINT uq_order_items_product
        UNIQUE (order_id, product_id);

CREATE TABLE api_idempotency (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash_version SMALLINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id UUID NOT NULL,
    response_order_number VARCHAR(50) NOT NULL,
    response_order_status VARCHAR(20) NOT NULL,
    response_created_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_api_idempotency
        PRIMARY KEY (customer_id, idempotency_key),
    CONSTRAINT uq_api_idempotency_order
        UNIQUE (order_id),
    CONSTRAINT fk_api_idempotency_order
        FOREIGN KEY (order_id, customer_id)
        REFERENCES orders (id, customer_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_api_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,100}$'),
    CONSTRAINT ck_api_idempotency_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_api_idempotency_hash_version
        CHECK (request_hash_version = 1),
    CONSTRAINT ck_api_idempotency_response_status
        CHECK (response_order_status = 'PENDING'),
    CONSTRAINT ck_api_idempotency_dates
        CHECK (created_at = response_created_at)
);
