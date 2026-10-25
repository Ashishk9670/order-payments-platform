package com.example.shop.order.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shop.order.security.ServiceTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Consumer side of contracts/catalog-get-product.v1.json: a server that behaves exactly as the contract says (its `example`
 * body, its status codes) must be understood by the real CatalogClient. The provider side lives in the catalog service.
 */
class CatalogContractConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theRealClientUnderstandsEveryInteractionOfTheContract() throws Exception {
        JsonNode contract = mapper.readTree(Files.readString(Path.of(System.getProperty("contracts.dir"), "catalog-get-product.v1.json")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            for (JsonNode i : contract.get("interactions")) {
                JsonNode response = i.get("response");
                byte[] body = (response.has("example") ? response.get("example").toString() : "{\"detail\":\"not found\"}")
                        .getBytes(StandardCharsets.UTF_8);
                if (response.has("schema")) {   // the contract must agree with itself
                    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(response.get("schema"));
                    assertThat(schema.validate(response.get("example"))).isEmpty();
                }
                server.createContext(i.get("request").get("path").asText(), ex -> {
                    ex.getResponseHeaders().add("Content-Type", response.get("contentType").asText());
                    ex.sendResponseHeaders(response.get("status").asInt(), body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
            }
            server.start();

            var resilience = new ResilienceConfig();
            var client = new CatalogClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000,
                    resilience.catalogCircuitBreaker(CircuitBreakerRegistry.ofDefaults()), resilience.catalogRetry(),
                    new ServiceTokens() {
                        @Override public String bearer() { return "t"; }
                        @Override public void invalidate() { }
                    });

            for (JsonNode i : contract.get("interactions")) {
                String sku = i.get("request").get("path").asText().replaceAll(".*/", "");
                BigDecimal price = client.priceOf(sku);
                if (i.get("response").get("status").asInt() == 200) {
                    assertThat(price).as(i.get("description").asText())
                            .isEqualByComparingTo(new BigDecimal(i.get("response").get("example").get("price").asText()));
                } else {
                    assertThat(price).as(i.get("description").asText()).isNull();   // 404 = unknown product, not an error
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
