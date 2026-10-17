package com.example.shop.payment.domain;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Configuration
@ConditionalOnProperty(name = "payment.gateway", havingValue = "http")
public class PspResilienceConfig {

    /** Only infrastructure failures count against the processor; a 4xx is a decision, not an outage. */
    static boolean transientFailure(Throwable t) {
        return t instanceof ResourceAccessException || t instanceof HttpServerErrorException;
    }

    @Bean
    public CircuitBreakerRegistry pspCircuitBreakerRegistry(MeterRegistry meters) {
        var registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    public CircuitBreaker pspCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("psp", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(PspResilienceConfig::transientFailure)
                .build());
    }

    @Bean
    public Retry pspRetry() {
        return Retry.of("psp", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(200, 2.0))
                .retryOnException(PspResilienceConfig::transientFailure)
                .build());
    }
}
