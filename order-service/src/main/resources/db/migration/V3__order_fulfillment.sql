-- Where the parcel is, as reported by the shipping service. NULL until the order ships.
ALTER TABLE orders ADD COLUMN fulfillment     VARCHAR(16);
ALTER TABLE orders ADD COLUMN carrier         VARCHAR(100);
ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(100);
