package com.example.shop.order.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key support for POST /orders: a client retry with the same key returns the original order instead of
 * creating a duplicate. Uses SET NX so two concurrent requests with the same key cannot both proceed.
 */
@Service
public class IdempotencyService {

    private static final String PENDING = "PENDING";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration PENDING_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public sealed interface Claim permits Claim.Acquired, Claim.Completed, Claim.InFlight {
        record Acquired() implements Claim {
        }

        record Completed(UUID orderId) implements Claim {
        }

        record InFlight() implements Claim {
        }
    }

    public Claim claim(String key) {
        String redisKey = redisKey(key);
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return new Claim.Acquired();
        }
        String value = redis.opsForValue().get(redisKey);
        if (value == null || PENDING.equals(value)) {
            return new Claim.InFlight();
        }
        return new Claim.Completed(UUID.fromString(value));
    }

    public void complete(String key, UUID orderId) {
        redis.opsForValue().set(redisKey(key), orderId.toString(), TTL);
    }

    public void release(String key) {
        redis.delete(redisKey(key));
    }

    public Optional<UUID> find(String key) {
        String v = redis.opsForValue().get(redisKey(key));
        return v == null || PENDING.equals(v) ? Optional.empty() : Optional.of(UUID.fromString(v));
    }

    private static String redisKey(String key) {
        return "idem:order:" + key;
    }
}
