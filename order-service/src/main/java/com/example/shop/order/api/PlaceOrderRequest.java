package com.example.shop.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record PlaceOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotEmpty @Size(max = 50) List<@Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) String sku,
            @Min(1) @Max(1000) int quantity,
            @NotNull @DecimalMin("0.01") BigDecimal unitPrice) {
    }
}
