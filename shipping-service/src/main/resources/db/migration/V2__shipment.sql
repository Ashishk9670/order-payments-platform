CREATE TABLE shipment (
    id              UUID         PRIMARY KEY,
    order_id        UUID         NOT NULL UNIQUE,   -- one shipment per order, enforced by the DB
    status          VARCHAR(16)  NOT NULL,
    carrier         VARCHAR(100),
    tracking_number VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    shipped_at      TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ
);
CREATE INDEX idx_shipment_status_created ON shipment (status, created_at);
