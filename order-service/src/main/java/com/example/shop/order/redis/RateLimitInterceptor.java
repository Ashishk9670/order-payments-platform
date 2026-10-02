package com.example.shop.order.redis;

import com.example.shop.order.api.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Fixed-window limiter shared by all instances (state lives in Redis). */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public RateLimitInterceptor(StringRedisTemplate redis, @Value("${ratelimit.requests-per-minute:120}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = "rl:" + request.getRemoteAddr() + ":" + (System.currentTimeMillis() / 60_000);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(70));
        }
        if (count != null && count > limitPerMinute) {
            response.setHeader("Retry-After", "60");
            throw new RateLimitExceededException("Rate limit of " + limitPerMinute + " requests/minute exceeded");
        }
        return true;
    }
}
