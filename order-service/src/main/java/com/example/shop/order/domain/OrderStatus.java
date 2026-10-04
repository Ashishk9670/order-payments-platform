package com.example.shop.order.domain;

public enum OrderStatus {
    PENDING, INVENTORY_RESERVED, CONFIRMED, REFUND_PENDING, CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}
