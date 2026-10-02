package com.example.shop.inventory.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Small distributed lock (SET NX PX + token-checked release). Locks are taken in sorted key order to avoid deadlocks
 * between orders that share SKUs. The lock sits OUTSIDE the DB transaction so it is released only after commit.
 */
@Component
public class RedisLock {

    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public <T> T withLocks(Collection<String> names, Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        List<String> held = new ArrayList<>();
        try {
            for (String name : names.stream().distinct().sorted().toList()) {
                String key = "lock:inventory:" + name;
                acquire(key, token);
                held.add(key);
            }
            return action.get();
        } finally {
            held.forEach(k -> redis.execute(RELEASE, List.of(k), token));
        }
    }

    private void acquire(String key, String token) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, LEASE))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Could not acquire lock " + key);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + key, e);
            }
        }
    }
}
