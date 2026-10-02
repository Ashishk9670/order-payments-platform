package com.example.shop.common.events;

/** Topic names. Versioned so a breaking schema change can ship as a new topic. See docs/asyncapi.yaml. */
public final class Topics {
    public static final String ORDER_CREATED = "order.created.v1";
    public static final String ORDER_CONFIRMED = "order.confirmed.v1";
    public static final String ORDER_CANCELLED = "order.cancelled.v1";
    public static final String INVENTORY_RESERVED = "inventory.reserved.v1";
    public static final String INVENTORY_FAILED = "inventory.failed.v1";
    public static final String PAYMENT_AUTHORIZED = "payment.authorized.v1";
    public static final String PAYMENT_FAILED = "payment.failed.v1";

    private Topics() {
    }
}
