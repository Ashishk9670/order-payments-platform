package com.example.shop.order.catalog;

import com.example.shop.order.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Prices come from the catalog service, never from the client. Calls are protected by a circuit breaker (stop hammering
 * a dead dependency) and a short retry (ride out blips); known prices are cached in Redis so the catalog is not on the
 * hot path of every order.
 */
@Component
public class CatalogClient {

    public record ProductDto(String sku, String name, BigDecimal price) implements Serializable {
    }

    private final RestClient http;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public CatalogClient(@Value("${catalog.url}") String baseUrl,
                         @Value("${catalog.timeout-ms:2000}") int timeoutMs,
                         CircuitBreaker catalogCircuitBreaker, Retry catalogRetry, ServiceTokens tokens) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String token;
                    try {
                        token = tokens.bearer();
                    } catch (RuntimeException e) {
                        // an IOException becomes a ResourceAccessException: transient, so retried and counted by the breaker
                        throw new java.io.IOException("could not obtain a service token", e);
                    }
                    if (token != null) {
                        request.getHeaders().setBearerAuth(token);
                    }
                    var response = execution.execute(request, body);
                    if (response.getStatusCode().value() == 401) {
                        tokens.invalidate();   // expired or rotated: the next attempt fetches a fresh token
                    }
                    return response;
                })
                .build();
        this.breaker = catalogCircuitBreaker;
        this.retry = catalogRetry;
    }

    /** @return the unit price, or null if the SKU does not exist (null is never cached). */
    @Cacheable(cacheNames = "catalog-price", key = "#sku", unless = "#result == null")
    public BigDecimal priceOf(String sku) {
        return guarded(() -> {
            try {
                ProductDto p = http.get().uri("/api/v1/products/{sku}", sku).retrieve().body(ProductDto.class);
                return p == null ? null : p.price();
            } catch (HttpClientErrorException.NotFound e) {
                return null;   // a definite answer, not a failure: must not trip the breaker
            }
        });
    }

    public List<ProductDto> list() {
        return guarded(() -> {
            ProductDto[] all = http.get().uri("/api/v1/products").retrieve().body(ProductDto[].class);
            return all == null ? List.<ProductDto>of() : List.of(all);
        });
    }

    private <T> T guarded(Supplier<T> call) {
        try {
            return Decorators.ofSupplier(call).withCircuitBreaker(breaker).withRetry(retry).get();
        } catch (CallNotPermittedException e) {
            throw new CatalogUnavailableException("Catalog circuit is open", e);
        } catch (RestClientException e) {
            throw new CatalogUnavailableException("Catalog call failed: " + e.getMessage(), e);
        }
    }
}
