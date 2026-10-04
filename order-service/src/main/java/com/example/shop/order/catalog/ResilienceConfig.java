package com.example.shop.order.catalog;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Configuration
class ResilienceConfig {

    /** Only infrastructure failures count; a 404 for an unknown SKU is a valid answer. */
    private static boolean transientFailure(Throwable t) {
        return t instanceof ResourceAccessException || t instanceof HttpServerErrorException;
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        var registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    CircuitBreaker catalogCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("catalog", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(ResilienceConfig::transientFailure)
                .build());
    }

    @Bean
    Retry catalogRetry() {
        return Retry.of("catalog", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(150))
                .retryOnException(ResilienceConfig::transientFailure)
                .build());
    }
}
