package com.example.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.InventoryExpired;
import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.order.catalog.CatalogClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Order service against real Postgres + Kafka + Redis. The catalog is stubbed; inventory and payment are played by the
 * test through Kafka, so every saga step is driven (and observed) explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ratelimit.requests-per-minute=100000",
        "security.jwt.secret=integration-test-secret-integration-test-0123456789",
        "security.dev-token.enabled=true"})
@Import(Containers.class)
class OrderFlowIT {

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired ObjectMapper mapper;
    @MockitoBean CatalogClient catalog;

    private static final String BODY = """
            {"customerId":"%s","items":[{"sku":"SKU-1001","quantity":2},{"sku":"SKU-1002","quantity":1}]}""";

    @BeforeEach
    void catalogPrices() {
        when(catalog.priceOf(anyString())).thenReturn(new BigDecimal("10.00"));
        when(catalog.priceOf("SKU-NOPE")).thenReturn(null);
    }

    private String token(String customer, boolean admin) {
        return http.postForObject("/dev/token", Map.of("customerId", customer, "admin", admin), JsonNode.class).get("token").asText();
    }

    private HttpHeaders as(String customer) {
        var h = new HttpHeaders();
        h.setBearerAuth(token(customer, false));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode place(String customer, String idempotencyKey) {
        var headers = as(customer);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        var response = http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(BODY.formatted(customer), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isIn(202, 200);
        return response.getBody();
    }

    private JsonNode get(String id, String customer) {
        return http.exchange("/api/v1/orders/" + id, HttpMethod.GET, new HttpEntity<>(as(customer)), JsonNode.class).getBody();
    }

    private String status(String id, String customer) {
        return get(id, customer).get("status").asText();
    }

    private void send(String topic, String orderId, Object event) throws Exception {
        kafka.send(topic, orderId, mapper.writeValueAsString(event)).get();
    }

    private String bootstrap() {
        return kafkaDetails.getBootstrapServers().get(0);
    }

    /** Drives a new order to CONFIRMED through the saga events. */
    private String confirmedOrder(String customer) throws Exception {
        String id = place(customer, null).get("id").asText();
        UUID orderId = UUID.fromString(id);
        send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, customer, new BigDecimal("30.00")));
        send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(orderId, UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CONFIRMED"));
        return id;
    }

    @Test
    void placingAnOrderStoresItAndPublishesOrderCreatedViaOutbox() throws Exception {
        try (var probe = new KafkaProbe(bootstrap(), Topics.ORDER_CREATED)) {
            JsonNode order = place("cust-outbox", null);
            String id = order.get("id").asText();

            assertThat(order.get("status").asText()).isEqualTo("PENDING");
            assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("30.00");

            var record = probe.awaitKey(id, Duration.ofSeconds(30)).orElseThrow(() -> new AssertionError("no OrderCreated event"));
            var event = mapper.readValue(record.value(), OrderCreated.class);
            assertThat(event.orderId()).hasToString(id);
            assertThat(event.items()).extracting(OrderCreated.Line::sku).containsExactlyInAnyOrder("SKU-1001", "SKU-1002");
            assertThat(event.totalAmount()).isEqualByComparingTo("30.00");
        }
    }

    @Test
    void happyPathSagaConfirmsOrderEvenThoughReadWasCachedWhilePending() throws Exception {
        try (var probe = new KafkaProbe(bootstrap(), Topics.ORDER_CONFIRMED)) {
            String customer = "cust-happy";
            String id = place(customer, null).get("id").asText();
            assertThat(status(id, customer)).isEqualTo("PENDING");   // populates the Redis cache

            UUID orderId = UUID.fromString(id);
            send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, customer, new BigDecimal("30.00")));
            send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(orderId, UUID.randomUUID()));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CONFIRMED"));
            assertThat(probe.awaitKey(id, Duration.ofSeconds(30))).isPresent();
        }
    }

    @Test
    void duplicateDeliveryOfSameEventIsHarmless() throws Exception {
        String customer = "cust-dup";
        String id = place(customer, null).get("id").asText();
        var reserved = InventoryReserved.of(UUID.fromString(id), customer, new BigDecimal("30.00"));
        send(Topics.INVENTORY_RESERVED, id, reserved);
        send(Topics.INVENTORY_RESERVED, id, reserved);
        send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(UUID.fromString(id), UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CONFIRMED"));
    }

    @Test
    void inventoryFailureCancelsTheOrderWithReason() throws Exception {
        String customer = "cust-nostock";
        String id = place(customer, null).get("id").asText();

        send(Topics.INVENTORY_FAILED, id, InventoryFailed.of(UUID.fromString(id), "Insufficient stock"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CANCELLED"));
        assertThat(get(id, customer).get("cancelReason").asText()).contains("Insufficient stock");
    }

    @Test
    void paymentFailureAfterReservationCancelsTheOrder() throws Exception {
        try (var probe = new KafkaProbe(bootstrap(), Topics.ORDER_CANCELLED)) {
            String customer = "cust-declined";
            String id = place(customer, null).get("id").asText();
            UUID orderId = UUID.fromString(id);

            send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, customer, new BigDecimal("30.00")));
            send(Topics.PAYMENT_FAILED, id, PaymentFailed.of(orderId, "declined"));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CANCELLED"));
            assertThat(probe.awaitKey(id, Duration.ofSeconds(30))).isPresent();
        }
    }

    @Test
    void refundSagaCancelsAConfirmedOrderOnceTheMoneyIsBack() throws Exception {
        String customer = "cust-refund";
        String id = confirmedOrder(customer);
        try (var requested = new KafkaProbe(bootstrap(), Topics.ORDER_CANCELLATION_REQUESTED);
             var cancelled = new KafkaProbe(bootstrap(), Topics.ORDER_CANCELLED)) {

            var response = http.exchange("/api/v1/orders/" + id + "/cancel", HttpMethod.POST,
                    new HttpEntity<>("{\"reason\":\"changed my mind\"}", as(customer)), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().get("status").asText()).isEqualTo("REFUND_PENDING");

            var request = requested.awaitKey(id, Duration.ofSeconds(30)).orElseThrow();
            assertThat(mapper.readValue(request.value(), OrderCancellationRequested.class).reason())
                    .isEqualTo("Cancelled by customer: changed my mind");
            assertThat(status(id, customer)).isEqualTo("REFUND_PENDING");   // still not cancelled: money not back yet

            send(Topics.PAYMENT_REFUNDED, id, PaymentRefunded.of(UUID.fromString(id), UUID.randomUUID()));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CANCELLED"));
            assertThat(cancelled.awaitKey(id, Duration.ofSeconds(30))).isPresent();
        }
    }

    @Test
    void anExpiredReservationCancelsTheOrderAndALatePaymentIsRefundedAutomatically() throws Exception {
        String customer = "cust-late-payment";
        String id = place(customer, null).get("id").asText();
        UUID orderId = UUID.fromString(id);
        send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, customer, new BigDecimal("30.00")));

        send(Topics.INVENTORY_EXPIRED, id, InventoryExpired.of(orderId, "Payment not received within PT30M"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CANCELLED"));
        assertThat(get(id, customer).get("cancelReason").asText()).contains("Payment not received");

        // the payment was in flight and lands after the cancellation: the money must be sent back
        try (var refunds = new KafkaProbe(bootstrap(), Topics.ORDER_CANCELLATION_REQUESTED)) {
            send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(orderId, UUID.randomUUID()));

            var request = refunds.awaitKey(id, Duration.ofSeconds(30)).orElseThrow();
            assertThat(mapper.readValue(request.value(), OrderCancellationRequested.class).reason()).contains("after the order was cancelled");
            assertThat(status(id, customer)).as("the order stays cancelled, it is never resurrected").isEqualTo("CANCELLED");
        }
    }

    @Test
    void refusedRefundLeavesTheOrderConfirmed() throws Exception {
        String customer = "cust-refund-refused";
        String id = confirmedOrder(customer);
        http.exchange("/api/v1/orders/" + id + "/cancel", HttpMethod.POST, new HttpEntity<>(as(customer)), JsonNode.class);

        send(Topics.PAYMENT_REFUND_FAILED, id, PaymentRefundFailed.of(UUID.fromString(id), "PSP declined"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CONFIRMED"));
        assertThat(get(id, customer).get("cancelReason").isNull()).isTrue();
    }

    @Test
    void onlyConfirmedOrdersCanBeCancelled() {
        String customer = "cust-pending";
        String id = place(customer, null).get("id").asText();

        var response = http.exchange("/api/v1/orders/" + id + "/cancel", HttpMethod.POST, new HttpEntity<>(as(customer)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() {
        String customer = "cust-idem";
        String key = "key-" + UUID.randomUUID();

        JsonNode first = place(customer, key);
        JsonNode second = place(customer, key);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        var list = http.exchange("/api/v1/orders?customerId=" + customer, HttpMethod.GET, new HttpEntity<>(as(customer)), JsonNode.class).getBody();
        assertThat(list).hasSize(1);
    }

    @Test
    void unknownProductIsRejectedWith422AndNothingIsStored() {
        var response = http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(
                "{\"customerId\":\"cust-422\",\"items\":[{\"sku\":\"SKU-NOPE\",\"quantity\":1}]}", as("cust-422")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var list = http.exchange("/api/v1/orders?customerId=cust-422", HttpMethod.GET, new HttpEntity<>(as("cust-422")), JsonNode.class).getBody();
        assertThat(list).isEmpty();
    }

    @Test
    void invalidRequestReturns400ProblemJson() {
        var response = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>("{\"customerId\":\"c\",\"items\":[]}", as("c")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() {
        assertThat(http.getForEntity("/api/v1/orders/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var forged = new HttpHeaders();
        forged.setBearerAuth("not.a.jwt");
        assertThat(http.exchange("/api/v1/products", HttpMethod.GET, new HttpEntity<>(forged), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customersCannotSeeEachOthersOrders() {
        String id = place("cust-owner", null).get("id").asText();

        assertThat(http.exchange("/api/v1/orders/" + id, HttpMethod.GET, new HttpEntity<>(as("cust-other")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/api/v1/orders?customerId=cust-owner", HttpMethod.GET, new HttpEntity<>(as("cust-other")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void graphQlReturnsOrdersWithItemsBatchLoaded() {
        String customer = "cust-gql";
        place(customer, null);
        place(customer, null);
        var query = Map.of("query", """
                { ordersByCustomer(customerId: "cust-gql") { id status totalAmount items { sku quantity } } }""");

        JsonNode body = http.exchange("/graphql", HttpMethod.POST, new HttpEntity<>(query, as(customer)), JsonNode.class).getBody();

        assertThat(body.has("errors")).as(body.toString()).isFalse();
        JsonNode orders = body.get("data").get("ordersByCustomer");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("items")).hasSize(2);
        assertThat(orders.get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void graphQlMutationsPlaceAndCancelThroughTheSameRules() throws Exception {
        String customer = "cust-gql-mut";
        var place = Map.of("query", "mutation($i: PlaceOrderInput!) { placeOrder(input: $i) { id status totalAmount } }",
                "variables", Map.of("i", Map.of("customerId", customer, "items", java.util.List.of(Map.of("sku", "SKU-1001", "quantity", 3)))));

        JsonNode placed = http.exchange("/graphql", HttpMethod.POST, new HttpEntity<>(place, as(customer)), JsonNode.class).getBody();

        assertThat(placed.has("errors")).as(placed.toString()).isFalse();
        assertThat(placed.get("data").get("placeOrder").get("totalAmount").asDouble()).isEqualTo(30.0);
        String id = placed.get("data").get("placeOrder").get("id").asText();
        UUID orderId = UUID.fromString(id);
        send(Topics.INVENTORY_RESERVED, id, InventoryReserved.of(orderId, customer, new BigDecimal("30.00")));
        send(Topics.PAYMENT_AUTHORIZED, id, PaymentAuthorized.of(orderId, UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(id, customer)).isEqualTo("CONFIRMED"));

        var cancel = Map.of("query", "mutation { cancelOrder(id: \"" + id + "\", reason: \"gql\") { status } }");
        JsonNode cancelled = http.exchange("/graphql", HttpMethod.POST, new HttpEntity<>(cancel, as(customer)), JsonNode.class).getBody();

        assertThat(cancelled.has("errors")).as(cancelled.toString()).isFalse();
        assertThat(cancelled.get("data").get("cancelOrder").get("status").asText()).isEqualTo("REFUND_PENDING");
    }

    @Test
    void unknownOrderReturns404() {
        var response = http.exchange("/api/v1/orders/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(as("anyone")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
