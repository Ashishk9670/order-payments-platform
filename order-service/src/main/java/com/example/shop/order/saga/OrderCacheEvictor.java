package com.example.shop.order.saga;

import com.example.shop.order.api.OrderChanged;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Evicts only after the status change is committed, so a concurrent read cannot re-cache the stale row. */
@Component
class OrderCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(OrderCacheEvictor.class);

    private final CacheManager cacheManager;

    OrderCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(OrderChanged event) {
        try {
            var cache = cacheManager.getCache("orders");
            if (cache != null) {
                cache.evict(event.orderId());
            }
        } catch (RuntimeException e) {
            // the entry expires on its own within the cache TTL; failing the already-committed transaction callback helps no one
            log.warn("Could not evict order {} from the cache (it will expire on its own): {}", event.orderId(), e.toString());
        }
    }
}
