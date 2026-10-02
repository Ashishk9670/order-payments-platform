CREATE TABLE orders (
    id            UUID           PRIMARY KEY,
    customer_id   VARCHAR(64)    NOT NULL,
    status        VARCHAR(32)    NOT NULL,
    total_amount  NUMERIC(19, 2) NOT NULL,
    cancel_reason VARCHAR(255),
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,
    version       BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_customer ON orders (customer_id, created_at DESC);

CREATE TABLE order_items (
    id         BIGSERIAL      PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku        VARCHAR(64)    NOT NULL,
    quantity   INT            NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
