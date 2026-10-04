package com.example.shop.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Prices are deliberately absent: the server prices every line from the catalog. */
public record PlaceOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotEmpty @Size(max = 50) List<@Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) String sku,
            @Min(1) @Max(1000) int quantity) {
    }
}
