package com.example.shop.order.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * The cache is an optimisation, never a dependency: when Redis is down every cache operation fails soft (a read becomes a miss,
 * a write or eviction is skipped), so requests are served from the database. Counted as cache.errors so it is visible.
 */
@Configuration
class ResilientCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ResilientCacheConfig.class);

    private final MeterRegistry meters;

    ResilientCacheConfig(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                failed("get", cache, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                failed("put", cache, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                failed("evict", cache, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                failed("clear", cache, e);
            }
        };
    }

    private void failed(String operation, Cache cache, RuntimeException e) {
        meters.counter("cache.errors", "cache", cache.getName(), "operation", operation).increment();
        log.warn("Cache {} on '{}' failed, continuing without the cache: {}", operation, cache.getName(), e.toString());
    }
}
