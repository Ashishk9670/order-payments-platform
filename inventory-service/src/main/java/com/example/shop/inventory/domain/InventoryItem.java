package com.example.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    private String sku;
    @Column(nullable = false)
    private int available;
    @Column(nullable = false)
    private int reserved;

    protected InventoryItem() {
    }

    public InventoryItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }
}
