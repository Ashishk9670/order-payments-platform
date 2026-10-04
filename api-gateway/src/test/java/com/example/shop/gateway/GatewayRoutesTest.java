package com.example.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Gateway wired for real against a stub "order service" (no Redis: the limiter fails open, which is tested in e2e). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {

    static HttpServer backend;
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastRequestId = new AtomicReference<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastRequestId.set(ex.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = ("backend:" + ex.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("gateway.order-service-url", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @Autowired WebTestClient client;

    @Test
    void orderApiGraphqlAndUiAreRoutedToTheOrderService() {
        for (String path : new String[] {"/api/v1/orders/abc", "/api/v1/products", "/graphql", "/graphiql", "/index.html", "/dev/token"}) {
            client.get().uri(path).exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("backend:" + path);
        }
    }

    @Test
    void internalServicesAreNotExposed() {
        client.get().uri("/api/v1/inventory/SKU-1").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/payments/order/x").exchange().expectStatus().isNotFound();
        client.put().uri("/api/v1/catalog/anything").exchange().expectStatus().isNotFound();
    }

    @Test
    void authorizationHeaderIsForwardedUntouched() {
        client.get().uri("/api/v1/orders/x").header("Authorization", "Bearer abc.def.ghi").exchange().expectStatus().isOk();
        assertThat(lastAuth.get()).isEqualTo("Bearer abc.def.ghi");
    }

    @Test
    void everyResponseCarriesARequestIdAndItReachesTheBackend() {
        var result = client.get().uri("/api/v1/orders/x").exchange().expectStatus().isOk().returnResult(String.class);
        String echoed = result.getResponseHeaders().getFirst("X-Request-Id");

        assertThat(echoed).isNotBlank();
        assertThat(lastRequestId.get()).isEqualTo(echoed);
    }

    @Test
    void aSaneClientSuppliedRequestIdIsPreservedAndAJunkOneIsReplaced() {
        client.get().uri("/api/v1/orders/x").header("X-Request-Id", "trace-12345678").exchange()
                .expectHeader().valueEquals("X-Request-Id", "trace-12345678");
        var junk = client.get().uri("/api/v1/orders/x").header("X-Request-Id", "bad id\twith spaces!").exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");
        assertThat(junk).matches("[0-9a-f-]{36}");
    }

    @Test
    void rateLimitKeysAreSeparatePerTokenAndStable() {
        assertThat(GatewayRoutes.sha256Prefix("Bearer a")).isNotEqualTo(GatewayRoutes.sha256Prefix("Bearer b"))
                .isEqualTo(GatewayRoutes.sha256Prefix("Bearer a")).hasSize(16);
    }
}
