package com.example.shop.order.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Caches a token until shortly before it expires, so callers do not mint or fetch one per request. */
abstract class CachingServiceTokens implements ServiceTokens {

    /** Refresh this long before the real expiry so a token never expires in flight. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    record Token(String value, Instant expiresAt) {
    }

    private final Clock clock;
    private Token cached;

    CachingServiceTokens(Clock clock) {
        this.clock = clock;
    }

    abstract Token fetch();

    @Override
    public synchronized String bearer() {
        if (cached == null || !clock.instant().isBefore(cached.expiresAt().minus(REFRESH_MARGIN))) {
            cached = fetch();
        }
        return cached.value();
    }

    @Override
    public synchronized void invalidate() {
        cached = null;
    }
}
