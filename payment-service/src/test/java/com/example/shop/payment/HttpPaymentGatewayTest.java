package com.example.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shop.payment.domain.HttpPaymentGateway;
import com.example.shop.payment.domain.PspResilienceConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The processor adapter against a throw-away local server, with the production resilience settings. */
class HttpPaymentGatewayTest {

    HttpServer psp;
    CircuitBreaker breaker;
    HttpPaymentGateway gateway;
    final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    final AtomicInteger flakyHits = new AtomicInteger();
    final AtomicInteger downHits = new AtomicInteger();
    final AtomicInteger rejectedHits = new AtomicInteger();
    volatile String chargeBody = "{\"status\":\"approved\"}";

    @BeforeEach
    void start() throws IOException {
        psp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        psp.createContext("/v1/charges", ex -> {
            record(ex);
            reply(ex, 200, chargeBody);
        });
        psp.createContext("/v1/refunds", ex -> {
            record(ex);
            reply(ex, 200, "{\"status\":\"approved\"}");
        });
        psp.createContext("/flaky/v1/charges", ex -> {
            record(ex);
            if (flakyHits.incrementAndGet() <= 2) {
                reply(ex, 503, "{}");
            } else {
                reply(ex, 200, "{\"status\":\"approved\"}");
            }
        });
        psp.createContext("/down/v1/charges", ex -> {
            downHits.incrementAndGet();
            reply(ex, 500, "{}");
        });
        psp.createContext("/rejecting/v1/charges", ex -> {
            rejectedHits.incrementAndGet();
            reply(ex, 402, "{\"error\":\"card_declined\"}");
        });
        psp.start();
    }

    @AfterEach
    void stop() {
        psp.stop(0);
    }

    private HttpPaymentGateway gatewayFor(String prefix, String apiKey) {
        var config = new PspResilienceConfig();
        breaker = config.pspCircuitBreaker(CircuitBreakerRegistry.ofDefaults());
        return new HttpPaymentGateway("http://127.0.0.1:" + psp.getAddress().getPort() + prefix, apiKey, 1000, breaker, config.pspRetry());
    }

    private void record(HttpExchange ex) {
        idempotencyKeys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
        authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void approvedChargeIsApprovedAndCarriesAnIdempotencyKeyAndCredentials() {
        var orderId = UUID.randomUUID();

        var result = gatewayFor("", "sk_test_123").authorize(orderId, new BigDecimal("10.00"));

        assertThat(result.approved()).isTrue();
        assertThat(idempotencyKeys).containsExactly("charge-" + orderId);
        assertThat(authHeaders).containsExactly("Bearer sk_test_123");
    }

    @Test
    void declineFromTheProcessorCarriesItsReason() {
        chargeBody = "{\"status\":\"declined\",\"reason\":\"Insufficient funds\"}";

        var result = gatewayFor("", "").authorize(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("Insufficient funds");
    }

    @Test
    void refundUsesItsOwnEndpointAndIdempotencyKey() {
        var orderId = UUID.randomUUID();

        var result = gatewayFor("", "").refund(orderId, BigDecimal.TEN);

        assertThat(result.approved()).isTrue();
        assertThat(idempotencyKeys).containsExactly("refund-" + orderId);
    }

    @Test
    void transientErrorsAreRetriedWithTheSameIdempotencyKeySoNoDoubleCharge() {
        var orderId = UUID.randomUUID();

        var result = gatewayFor("/flaky", "").authorize(orderId, BigDecimal.TEN);

        assertThat(result.approved()).isTrue();
        assertThat(flakyHits.get()).isEqualTo(3);
        assertThat(idempotencyKeys).hasSize(3).containsOnly("charge-" + orderId);
    }

    @Test
    void aProcessorThatStaysDownYieldsADeclineNotAnExceptionAndTheCircuitOpens() {
        var gw = gatewayFor("/down", "");

        var first = gw.authorize(UUID.randomUUID(), BigDecimal.TEN);
        var second = gw.authorize(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(first.approved()).isFalse();
        assertThat(first.declineReason()).isEqualTo("Payment provider unavailable");
        assertThat(second.approved()).isFalse();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int hitsWhenOpen = downHits.get();
        var third = gw.authorize(UUID.randomUUID(), BigDecimal.TEN);
        assertThat(third.declineReason()).isEqualTo("Payment provider unavailable");
        assertThat(downHits.get()).as("no request reaches a processor whose circuit is open").isEqualTo(hitsWhenOpen);
    }

    @Test
    void aFourXxIsAFinalAnswerNotRetriedAndNotCountedAgainstTheProcessor() {
        var gw = gatewayFor("/rejecting", "");

        for (int i = 0; i < 12; i++) {
            var result = gw.authorize(UUID.randomUUID(), BigDecimal.TEN);
            assertThat(result.approved()).isFalse();
            assertThat(result.declineReason()).contains("402");
        }

        assertThat(rejectedHits.get()).as("one call per attempt, no retries").isEqualTo(12);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unreachableProcessorIsADecline() {
        var gw = gatewayFor("", "");
        psp.stop(0);

        var result = gw.authorize(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("Payment provider unavailable");
    }
}
