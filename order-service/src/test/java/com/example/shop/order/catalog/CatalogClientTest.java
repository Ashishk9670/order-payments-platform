package com.example.shop.order.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shop.order.api.CatalogUnavailableException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real HTTP against a throw-away local server, with the production circuit-breaker and retry settings. */
class CatalogClientTest {

    HttpServer server;
    AtomicInteger flakyHits = new AtomicInteger();
    AtomicInteger downHits = new AtomicInteger();
    CircuitBreaker breaker;
    CatalogClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/products/OK", ex -> reply(ex, 200, "{\"sku\":\"OK\",\"name\":\"Fine\",\"price\":12.34}"));
        server.createContext("/api/v1/products/NOPE", ex -> reply(ex, 404, "{\"detail\":\"Unknown product NOPE\"}"));
        server.createContext("/api/v1/products/FLAKY", ex -> {
            if (flakyHits.incrementAndGet() <= 2) {
                reply(ex, 500, "{}");
            } else {
                reply(ex, 200, "{\"sku\":\"FLAKY\",\"name\":\"Eventually\",\"price\":5.00}");
            }
        });
        server.createContext("/api/v1/products/DOWN", ex -> {
            downHits.incrementAndGet();
            reply(ex, 500, "{}");
        });
        server.createContext("/api/v1/products", ex -> reply(ex, 200,
                "[{\"sku\":\"A\",\"name\":\"Alpha\",\"price\":1.00},{\"sku\":\"B\",\"name\":\"Beta\",\"price\":2.00}]"));
        server.start();

        var resilience = new ResilienceConfig();
        breaker = resilience.catalogCircuitBreaker(CircuitBreakerRegistry.ofDefaults());
        client = new CatalogClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, breaker, resilience.catalogRetry());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void returnsThePriceOfAKnownProduct() {
        assertThat(client.priceOf("OK")).isEqualByComparingTo("12.34");
    }

    @Test
    void unknownProductIsNullAndDoesNotCountAsAFailure() {
        for (int i = 0; i < 20; i++) {
            assertThat(client.priceOf("NOPE")).isNull();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void transientServerErrorsAreRetried() {
        assertThat(client.priceOf("FLAKY")).isEqualByComparingTo("5.00");
        assertThat(flakyHits.get()).isEqualTo(3);
    }

    @Test
    void persistentFailureOpensTheCircuitAndStopsCallingTheCatalog() {
        assertThatThrownBy(() -> client.priceOf("DOWN")).isInstanceOf(CatalogUnavailableException.class);
        assertThatThrownBy(() -> client.priceOf("DOWN")).isInstanceOf(CatalogUnavailableException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int hitsWhenOpened = downHits.get();
        assertThatThrownBy(() -> client.priceOf("DOWN"))
                .isInstanceOf(CatalogUnavailableException.class).hasMessageContaining("circuit is open");
        assertThat(downHits.get()).as("no request reaches the dead service while open").isEqualTo(hitsWhenOpened);
    }

    @Test
    void unreachableCatalogIsReportedAsUnavailable() {
        server.stop(0);
        assertThatThrownBy(() -> client.priceOf("OK")).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void listsProducts() {
        assertThat(client.list()).extracting(CatalogClient.ProductDto::sku).containsExactly("A", "B");
    }
}
