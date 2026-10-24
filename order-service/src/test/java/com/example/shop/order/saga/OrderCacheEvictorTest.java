package com.example.shop.order.saga;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.order.api.OrderChanged;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;

class OrderCacheEvictorTest {

    private final CacheManager manager = mock(CacheManager.class);
    private final Cache cache = mock(Cache.class);

    @Test
    void evictsTheChangedOrder() {
        var id = UUID.randomUUID();
        when(manager.getCache("orders")).thenReturn(cache);

        new OrderCacheEvictor(manager).on(new OrderChanged(id));

        verify(cache).evict(id);
    }

    @Test
    void aRedisFailureAfterCommitDoesNotPropagate() {
        when(manager.getCache("orders")).thenReturn(cache);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down")).when(cache).evict(org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> new OrderCacheEvictor(manager).on(new OrderChanged(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void aMissingCacheIsIgnored() {
        when(manager.getCache("orders")).thenReturn(null);

        assertThatCode(() -> new OrderCacheEvictor(manager).on(new OrderChanged(UUID.randomUUID()))).doesNotThrowAnyException();
    }
}
