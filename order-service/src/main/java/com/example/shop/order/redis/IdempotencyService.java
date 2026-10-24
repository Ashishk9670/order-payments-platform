package com.example.shop.order.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key support for POST /orders: a client retry with the same key returns the original order instead of
 * creating a duplicate. Uses SET NX so two concurrent requests with the same key cannot both proceed.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

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

    /** @throws IdempotencyUnavailableException when Redis cannot be reached: better to refuse than to risk a duplicate order. */
    public Claim claim(String key) {
        String redisKey = redisKey(key);
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return new Claim.Acquired();
            }
            String value = redis.opsForValue().get(redisKey);
            if (value == null || PENDING.equals(value)) {
                return new Claim.InFlight();
            }
            return new Claim.Completed(UUID.fromString(value));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IdempotencyUnavailableException(e);
        }
    }

    /** The order already exists at this point, so a Redis failure only means a retry will not be recognised as a replay. */
    public void complete(String key, UUID orderId) {
        try {
            redis.opsForValue().set(redisKey(key), orderId.toString(), TTL);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Could not record the result of idempotency key (order {} exists): {}", orderId, e.toString());
        }
    }

    public void release(String key) {
        try {
            redis.delete(redisKey(key));
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Could not release an idempotency key; it expires on its own: {}", e.toString());
        }
    }

    public Optional<UUID> find(String key) {
        String v = redis.opsForValue().get(redisKey(key));
        return v == null || PENDING.equals(v) ? Optional.empty() : Optional.of(UUID.fromString(v));
    }

    private static String redisKey(String key) {
        return "idem:order:" + key;
    }
}
