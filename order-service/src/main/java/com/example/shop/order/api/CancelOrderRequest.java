package com.example.shop.order.api;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 200) String reason) {
}
