package com.example.shop.order.security;

/** Credentials this service presents when it calls another platform service (for example the catalog). */
public interface ServiceTokens {

    /** @return a currently valid bearer token, or null when the service has none configured. */
    String bearer();

    /** Drop any cached token, for example after the callee answered 401, so the next call gets a fresh one. */
    void invalidate();
}
