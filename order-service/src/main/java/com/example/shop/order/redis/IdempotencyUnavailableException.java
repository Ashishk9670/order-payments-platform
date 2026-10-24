package com.example.shop.order.redis;

/** The idempotency store (Redis) cannot be reached, so a request that carries an Idempotency-Key cannot be honoured safely. */
public class IdempotencyUnavailableException extends RuntimeException {
    public IdempotencyUnavailableException(Throwable cause) {
        super("Idempotency cannot be guaranteed right now", cause);
    }
}
