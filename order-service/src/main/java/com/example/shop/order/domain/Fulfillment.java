package com.example.shop.order.domain;

/** Physical progress of a CONFIRMED order, reported by the shipping service. Absent (null) until the parcel leaves. */
public enum Fulfillment {
    SHIPPED, DELIVERED
}
