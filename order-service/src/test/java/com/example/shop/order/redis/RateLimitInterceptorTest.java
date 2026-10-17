package com.example.shop.order.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void firstRequestInWindowSetsExpiry() {
        when(ops.increment(anyString())).thenReturn(1L);
        var interceptor = new RateLimitInterceptor(redis, 3);

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
        verify(redis).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void requestAtTheLimitIsAllowed() {
        when(ops.increment(anyString())).thenReturn(3L);
        var interceptor = new RateLimitInterceptor(redis, 3);

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void requestOverTheLimitIsRejectedWithRetryAfter() {
        when(ops.increment(anyString())).thenReturn(4L);
        var interceptor = new RateLimitInterceptor(redis, 3);
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), response, new Object()))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }
}
