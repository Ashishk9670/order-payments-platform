package com.example.shop.order.api;

public class UnknownProductException extends RuntimeException {
    public UnknownProductException(String sku) {
        super("Unknown product " + sku);
    }
}
