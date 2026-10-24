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

    public static final String ORDER_CANCELLATION_REQUESTED = "order.cancellation-requested.v1";
    public static final String PAYMENT_REFUNDED = "payment.refunded.v1";
    public static final String PAYMENT_REFUND_FAILED = "payment.refund-failed.v1";

    /** Every topic of the platform, for validation (for example: a replay may only target a known topic). */
    public static java.util.List<String> all() {
        return java.util.List.of(ORDER_CREATED, ORDER_CONFIRMED, ORDER_CANCELLED, INVENTORY_RESERVED, INVENTORY_FAILED,
                PAYMENT_AUTHORIZED, PAYMENT_FAILED, ORDER_CANCELLATION_REQUESTED, PAYMENT_REFUNDED, PAYMENT_REFUND_FAILED);
    }

    private Topics() {
    }
}
