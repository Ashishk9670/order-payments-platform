package com.example.shop.catalog;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String sku) {
        super("Unknown product " + sku);
    }
}
