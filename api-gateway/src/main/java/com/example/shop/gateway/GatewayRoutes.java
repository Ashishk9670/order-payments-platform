package com.example.shop.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * One public entry point. Everything customer-facing is routed to the order service; inventory, payment and catalog
 * admin APIs are intentionally NOT routed (they are internal). Authentication stays in the order service: the gateway
 * forwards the Authorization header untouched, so there is a single place that validates tokens.
 */
@Configuration
class GatewayRoutes {

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, RedisRateLimiter limiter, KeyResolver key,
                        @Value("${gateway.order-service-url}") String orderServiceUrl) {
        return builder.routes()
                .route("order-service", r -> r
                        .path("/api/v1/orders/**", "/api/v1/products/**", "/graphql", "/graphql/**", "/graphiql/**",
                                "/dev/token", "/", "/index.html")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(key)))
                        .uri(orderServiceUrl))
                .build();
    }

    /** Token bucket shared across gateway instances (state in Redis). */
    @Bean
    RedisRateLimiter redisRateLimiter(@Value("${gateway.rate-limit.replenish-per-second:50}") int replenish,
                                      @Value("${gateway.rate-limit.burst:100}") int burst) {
        return new RedisRateLimiter(replenish, burst, 1);
    }

    /** Per caller: a hash of the bearer token when present (so one noisy customer cannot starve others), else the IP. */
    @Bean
    KeyResolver callerKeyResolver() {
        return exchange -> {
            String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (auth != null && !auth.isBlank()) {
                return Mono.just("tok:" + sha256Prefix(auth));
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress()));
        };
    }

    static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
