CREATE TABLE payment (
    id             UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL UNIQUE,   -- one payment per order, enforced by the DB
    amount         NUMERIC(19, 2) NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    failure_reason VARCHAR(255),
    created_at     TIMESTAMPTZ    NOT NULL
);
