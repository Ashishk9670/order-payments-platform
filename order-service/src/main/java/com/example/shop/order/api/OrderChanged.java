package com.example.shop.order.api;

import java.util.UUID;

/** Spring application event, published inside a transaction and handled after commit to evict the cache. */
public record OrderChanged(UUID orderId) {
}
