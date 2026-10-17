package com.example.shop.payment.gateway;

import com.example.shop.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for a real card processor (set payment.gateway=http, psp.url, psp.api-key).
 *
 * <ul>
 *   <li>Every call carries an Idempotency-Key derived from the order id, so a retry after a timeout can never charge
 *       or refund twice: the processor replays the original result.</li>
 *   <li>Transient failures (network, 5xx) are retried, and a circuit breaker stops hammering a dead processor.</li>
 *   <li>If the processor stays unavailable the result is a DECLINE, not an exception: the saga then compensates
 *       (PaymentFailed releases stock and cancels the order; a failed refund leaves the order confirmed) instead of
 *       retrying forever or leaving the order half-done.</li>
 *   <li>4xx answers are definite business decisions: not retried and not counted as processor failures.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "http")
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    record Request(UUID orderId, BigDecimal amount) {
    }

    record Response(String status, String reason) {
    }

    private final RestClient http;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public HttpPaymentGateway(@Value("${psp.url}") String baseUrl, @Value("${psp.api-key:}") String apiKey,
                              @Value("${psp.timeout-ms:3000}") int timeoutMs,
                              CircuitBreaker pspCircuitBreaker, Retry pspRetry) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        var builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (!apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);   // never logged
        }
        this.http = builder.build();
        this.breaker = pspCircuitBreaker;
        this.retry = pspRetry;
    }

    @Override
    public Result authorize(UUID orderId, BigDecimal amount) {
        return call("/v1/charges", "charge-" + orderId, new Request(orderId, amount));
    }

    @Override
    public Result refund(UUID orderId, BigDecimal amount) {
        return call("/v1/refunds", "refund-" + orderId, new Request(orderId, amount));
    }

    private Result call(String path, String idempotencyKey, Request body) {
        try {
            Response response = Decorators.ofSupplier(() -> post(path, idempotencyKey, body))
                    .withCircuitBreaker(breaker).withRetry(retry).get();
            return response != null && "approved".equalsIgnoreCase(response.status())
                    ? Result.approve()
                    : Result.decline(response == null || response.reason() == null ? "Declined by processor" : response.reason());
        } catch (CallNotPermittedException e) {
            log.warn("Payment processor circuit is open; declining {}", idempotencyKey);
            return Result.decline("Payment provider unavailable");
        } catch (RestClientException e) {
            log.warn("Payment processor call failed after retries for {}: {}", idempotencyKey, e.getMessage());
            return Result.decline("Payment provider unavailable");
        }
    }

    private Response post(String path, String idempotencyKey, Request body) {
        try {
            return http.post().uri(path).header("Idempotency-Key", idempotencyKey).body(body).retrieve().body(Response.class);
        } catch (HttpClientErrorException e) {
            // 4xx = the processor understood and said no. A final answer: no retry, no breaker penalty.
            return new Response("declined", "Rejected by processor (" + e.getStatusCode().value() + ")");
        }
    }
}
