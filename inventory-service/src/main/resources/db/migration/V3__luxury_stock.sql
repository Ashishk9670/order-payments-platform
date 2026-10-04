-- stock for the catalog's high-value demo product (used to demo the payment-decline saga path)
INSERT INTO inventory_item (sku, available) VALUES ('SKU-LUXURY', 50) ON CONFLICT (sku) DO NOTHING;
