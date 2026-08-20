CREATE TABLE orders (
    id UUID PRIMARY KEY,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    rejection_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_orders_total_amount
        CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_rejection_reason
        CHECK (
            (status = 'REJECTED' AND rejection_reason IS NOT NULL AND length(trim(rejection_reason)) > 0)
            OR (status <> 'REJECTED' AND rejection_reason IS NULL)
        ),
    CONSTRAINT ck_orders_dates
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL,
    subtotal NUMERIC(19, 2) NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT uq_order_items_position
        UNIQUE (order_id, position),
    CONSTRAINT ck_order_items_product_name
        CHECK (length(trim(product_name)) > 0),
    CONSTRAINT ck_order_items_quantity
        CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price
        CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_subtotal
        CHECK (subtotal = unit_price * quantity),
    CONSTRAINT ck_order_items_position
        CHECK (position >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_product_id ON order_items (product_id);
