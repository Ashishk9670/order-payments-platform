CREATE TABLE inventory_item (
    sku       VARCHAR(64) PRIMARY KEY,
    available INT NOT NULL CHECK (available >= 0),
    reserved  INT NOT NULL DEFAULT 0 CHECK (reserved >= 0)
);

CREATE TABLE reservation (
    id       BIGSERIAL   PRIMARY KEY,
    order_id UUID        NOT NULL,
    sku      VARCHAR(64) NOT NULL,
    quantity INT         NOT NULL CHECK (quantity > 0),
    status   VARCHAR(16) NOT NULL
);
CREATE INDEX idx_reservation_order ON reservation (order_id, status);

-- demo catalog
INSERT INTO inventory_item (sku, available) VALUES ('SKU-1001', 100), ('SKU-1002', 50), ('SKU-1003', 5);
