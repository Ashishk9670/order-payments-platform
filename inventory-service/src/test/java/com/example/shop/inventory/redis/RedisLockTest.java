package com.example.shop.inventory.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisLockTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;
    RedisLock lock;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        lock = new RedisLock(redis);
    }

    @Test
    void locksAreTakenInSortedOrderWithoutDuplicatesToAvoidDeadlocks() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        String result = lock.withLocks(List.of("B", "A", "B"), () -> "done");

        assertThat(result).isEqualTo("done");
        var order = inOrder(ops);
        order.verify(ops).setIfAbsent(eq("lock:inventory:A"), any(), any(Duration.class));
        order.verify(ops).setIfAbsent(eq("lock:inventory:B"), any(), any(Duration.class));
        org.mockito.Mockito.verifyNoMoreInteractions(ops);
    }

    @Test
    void everyLockIsReleasedWithTheTokenItWasTakenWith() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        var token = ArgumentCaptor.forClass(String.class);

        lock.withLocks(List.of("A", "B"), () -> null);

        verify(ops, org.mockito.Mockito.times(2)).setIfAbsent(any(), token.capture(), any(Duration.class));
        assertThat(token.getAllValues()).hasSize(2).containsOnly(token.getValue());   // one token for the whole critical section
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:inventory:A")), eq(token.getValue()));
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:inventory:B")), eq(token.getValue()));
    }

    @Test
    void locksAreReleasedEvenWhenTheActionThrows() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> lock.withLocks(List.of("A"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        verify(redis, atLeastOnce()).execute(any(RedisScript.class), eq(List.of("lock:inventory:A")), any());
    }

    @Test
    void waitsForAContendedLockAndThenProceeds() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false, false, true);

        String result = lock.withLocks(List.of("A"), () -> "got it");

        assertThat(result).isEqualTo("got it");
        verify(ops, org.mockito.Mockito.times(3)).setIfAbsent(any(), any(), any(Duration.class));
    }
}
