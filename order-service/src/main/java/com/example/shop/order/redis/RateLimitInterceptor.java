package com.example.shop.order.redis;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Fixed-window limiter shared by all instances (state lives in Redis). */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public RateLimitInterceptor(StringRedisTemplate redis, @Value("${ratelimit.requests-per-minute:120}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = "rl:" + request.getRemoteAddr() + ":" + (System.currentTimeMillis() / 60_000);
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(70));
            }
        } catch (RuntimeException e) {
            // protection must not become an outage: without Redis the limiter lets requests through (the gateway still limits)
            log.warn("Rate limiter unavailable, allowing the request: {}", e.toString());
            return true;
        }
        if (count != null && count > limitPerMinute) {
            response.setHeader("Retry-After", "60");
            throw new RateLimitExceededException("Rate limit of " + limitPerMinute + " requests/minute exceeded");
        }
        return true;
    }
}
