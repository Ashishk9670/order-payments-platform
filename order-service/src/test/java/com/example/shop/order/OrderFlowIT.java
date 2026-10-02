package com.example.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

/** Order service against real Postgres + Kafka + Redis; the other services are played by the test via Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimit.requests-per-minute=100000")
@Import(Containers.class)
class OrderFlowIT {

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired ObjectMapper mapper;

    private static final String BODY = """
            {"customerId":"%s","items":[{"sku":"SKU-1001","quantity":2,"unitPrice":10.00},{"sku":"SKU-1002","quantity":1,"unitPrice":5.50}]}""";

    private JsonNode place(String customer, String idempotencyKey) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        var response = http.postForEntity("/api/v1/orders", new HttpEntity<>(BODY.formatted(customer), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isIn(202, 200);
        return response.getBody();
    }

    private String status(String id) {
        return http.getForObject("/api/v1/orders/" + id, JsonNode.class).get("status").asText();
    }

    private void send(String topic, String orderId, Object event) throws Exception {
        kafka.send(topic, orderId, mapper.writeValueAsString(event)).get();
    }

    @Test
    void placingAnOrderStoresItAndPublishesOrderCreatedViaOutbox() throws Exception {
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.ORDER_CREATED)) {
            JsonNode order = place("cust-outbox", null);
            String id = order.get("id").asText();

            assertThat(order.get("status").asText()).isEqualTo("PENDING");
            assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("25.50");

            var record = probe.awaitKey(id, Duration.ofSeconds(20)).orElseThrow(() -> new AssertionError("no OrderCreated event"));
            var event = mapper.readValue(record.value(), OrderCreated.class);
            assertThat(event.orderId()).hasToString(id);
            assertThat(event.items()).extracting(OrderCreated.Line::sku).containsExactlyInAnyOrder("SKU-1001", "SKU-1002");
            assertThat(event.totalAmount()).isEqualByComparingTo("25.50");
        }
    }

    @Test
    void happyPathSagaConfirmsOrderEvenThoughReadWasCachedWhilePending() throws Exception {
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.ORDER_CONFIRMED)) {
            String id = place("cust-happy", null).get("id").asText();
            UUID orderId = UUID.fromString(id);
            assertThat(status(id)).isEqualTo("PENDING");   // populates the Redis cache

            send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, "cust-happy", new java.math.BigDecimal("25.50")));
            send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(orderId, UUID.randomUUID()));

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("CONFIRMED"));
            assertThat(probe.awaitKey(id, Duration.ofSeconds(20))).isPresent();
        }
    }

    @Test
    void duplicateDeliveryOfSameEventIsHarmless() throws Exception {
        String id = place("cust-dup", null).get("id").asText();
        var reserved = InventoryReserved.of(UUID.fromString(id), "cust-dup", new java.math.BigDecimal("25.50"));
        send(Topics.INVENTORY_RESERVED, id, reserved);
        send(Topics.INVENTORY_RESERVED, id, reserved);
        send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(UUID.fromString(id), UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("CONFIRMED"));
    }

    @Test
    void inventoryFailureCancelsTheOrderWithReason() throws Exception {
        String id = place("cust-nostock", null).get("id").asText();

        send(Topics.INVENTORY_FAILED, id, InventoryFailed.of(UUID.fromString(id), "Insufficient stock"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("CANCELLED"));
        assertThat(http.getForObject("/api/v1/orders/" + id, JsonNode.class).get("cancelReason").asText())
                .contains("Insufficient stock");
    }

    @Test
    void paymentFailureAfterReservationCancelsTheOrder() throws Exception {
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.ORDER_CANCELLED)) {
            String id = place("cust-declined", null).get("id").asText();
            UUID orderId = UUID.fromString(id);

            send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, "cust-declined", new java.math.BigDecimal("25.50")));
            send(Topics.PAYMENT_FAILED, id, PaymentFailed.of(orderId, "declined"));

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("CANCELLED"));
            assertThat(probe.awaitKey(id, Duration.ofSeconds(20))).isPresent();
        }
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() {
        String key = "key-" + UUID.randomUUID();

        JsonNode first = place("cust-idem", key);
        JsonNode second = place("cust-idem", key);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        var list = http.getForObject("/api/v1/orders?customerId=cust-idem", JsonNode.class);
        assertThat(list).hasSize(1);
    }

    @Test
    void invalidRequestReturns400ProblemJson() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity("/api/v1/orders",
                new HttpEntity<>("{\"customerId\":\"\",\"items\":[]}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
    }

    @Test
    void graphQlReturnsOrdersWithItemsBatchLoaded() {
        place("cust-gql", null);
        place("cust-gql", null);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var query = Map.of("query", """
                { ordersByCustomer(customerId: "cust-gql") { id status totalAmount items { sku quantity } } }""");

        JsonNode body = http.postForObject("/graphql", new HttpEntity<>(query, headers), JsonNode.class);

        assertThat(body.has("errors")).as(body.toString()).isFalse();
        JsonNode orders = body.get("data").get("ordersByCustomer");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("items")).hasSize(2);
        assertThat(orders.get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void unknownOrderReturns404() {
        var response = http.getForEntity("/api/v1/orders/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
