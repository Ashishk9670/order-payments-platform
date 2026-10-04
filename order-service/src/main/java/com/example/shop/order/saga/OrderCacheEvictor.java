package com.example.shop.order.saga;

import com.example.shop.order.api.OrderChanged;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Evicts only after the status change is committed, so a concurrent read cannot re-cache the stale row. */
@Component
class OrderCacheEvictor {

    private final CacheManager cacheManager;

    OrderCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(OrderChanged event) {
        var cache = cacheManager.getCache("orders");
        if (cache != null) {
            cache.evict(event.orderId());
        }
    }
}
