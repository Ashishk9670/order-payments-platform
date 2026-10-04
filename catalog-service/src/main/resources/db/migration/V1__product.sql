CREATE TABLE product (
    sku        VARCHAR(64)    PRIMARY KEY,
    name       VARCHAR(200)   NOT NULL,
    price      NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    updated_at TIMESTAMPTZ    NOT NULL
);

INSERT INTO product (sku, name, price, updated_at) VALUES
    ('SKU-1001', 'Standard widget', 19.99, now()),
    ('SKU-1002', 'Premium widget', 49.50, now()),
    ('SKU-1003', 'Limited-edition widget', 99.00, now()),
    ('SKU-LUXURY', 'Gold-plated widget', 20000.00, now());   -- above the simulated card limit: handy for decline demos
