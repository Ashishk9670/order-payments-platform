package com.example.shop.order.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;
    IdempotencyService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new IdempotencyService(redis);
    }

    @Test
    void firstCallerAcquires() {
        when(ops.setIfAbsent(eq("idem:order:k"), eq("PENDING"), any(Duration.class))).thenReturn(true);
        assertThat(service.claim("k")).isInstanceOf(IdempotencyService.Claim.Acquired.class);
    }

    @Test
    void secondCallerSeesCompletedOrderId() {
        var id = UUID.randomUUID();
        when(ops.setIfAbsent(eq("idem:order:k"), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get("idem:order:k")).thenReturn(id.toString());
        assertThat(service.claim("k")).isEqualTo(new IdempotencyService.Claim.Completed(id));
    }

    @Test
    void secondCallerWhileFirstIsRunningSeesInFlight() {
        when(ops.setIfAbsent(eq("idem:order:k"), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get("idem:order:k")).thenReturn("PENDING");
        assertThat(service.claim("k")).isInstanceOf(IdempotencyService.Claim.InFlight.class);
    }

    @Test
    void completeStoresOrderIdWithTtl() {
        var id = UUID.randomUUID();
        service.complete("k", id);
        verify(ops).set("idem:order:k", id.toString(), Duration.ofHours(24));
    }

    @Test
    void claimRefusesWhenRedisIsDownBecauseItCannotPromiseIdempotency() {
        when(ops.setIfAbsent(eq("idem:order:k"), eq("PENDING"), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim("k")).isInstanceOf(IdempotencyUnavailableException.class);
    }

    @Test
    void recordingTheResultOrReleasingTheKeyNeverFailsAnOrderThatAlreadyExists() {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(ops).set(eq("idem:order:k"), any(), any(Duration.class));
        when(redis.delete("idem:order:k")).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> {
            service.complete("k", UUID.randomUUID());
            service.release("k");
        }).doesNotThrowAnyException();
    }
}
