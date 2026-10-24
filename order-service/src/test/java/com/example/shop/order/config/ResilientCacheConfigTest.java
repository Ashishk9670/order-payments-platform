package com.example.shop.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.RedisConnectionFailureException;

class ResilientCacheConfigTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Cache cache = mock(Cache.class);

    ResilientCacheConfigTest() {
        when(cache.getName()).thenReturn("orders");
    }

    @Test
    void aCacheFailureIsSwallowedAndCountedForEveryOperation() {
        var handler = new ResilientCacheConfig(meters).errorHandler();
        var down = new RedisConnectionFailureException("redis down");

        assertThatCode(() -> {
            handler.handleCacheGetError(down, cache, "k");
            handler.handleCachePutError(down, cache, "k", "v");
            handler.handleCacheEvictError(down, cache, "k");
            handler.handleCacheClearError(down, cache);
        }).doesNotThrowAnyException();

        for (String op : new String[] {"get", "put", "evict", "clear"}) {
            assertThat(meters.counter("cache.errors", "cache", "orders", "operation", op).count()).as(op).isEqualTo(1.0);
        }
    }
}
