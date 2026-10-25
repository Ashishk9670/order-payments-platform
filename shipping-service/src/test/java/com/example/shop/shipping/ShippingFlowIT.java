package com.example.shop.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.OrderDelivered;
import com.example.shop.common.events.OrderShipped;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Shipping against real Postgres and Kafka: confirmed orders queue up, operators ship and deliver, cancellation wins races. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=integration-test-secret-integration-test-0123456789")
@Import(Containers.class)
class ShippingFlowIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder jwtEncoder;

    private HttpHeaders headers(String role) {
        var now = java.time.Instant.now();
        var claims = JwtClaimsSet.builder().subject("it").issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", java.util.List.of(role)).build();
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode shipmentOf(UUID orderId) {
        return http.exchange("/api/v1/shipments/order/" + orderId, HttpMethod.GET, new HttpEntity<>(headers("ADMIN")), JsonNode.class).getBody();
    }

    private HttpStatus post(UUID orderId, String action, Map<String, String> body) {
        return (HttpStatus) http.exchange("/api/v1/shipments/order/" + orderId + "/" + action, HttpMethod.POST,
                new HttpEntity<>(body, headers("ADMIN")), String.class).getStatusCode();
    }

    private void confirm(OrderConfirmed e) throws Exception {
        kafka.send(Topics.ORDER_CONFIRMED, e.orderId().toString(), mapper.writeValueAsString(e)).get();
    }

    private void awaitShipment(UUID orderId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(http.exchange("/api/v1/shipments/order/" + orderId, HttpMethod.GET, new HttpEntity<>(headers("ADMIN")),
                        String.class).getStatusCode()).isEqualTo(HttpStatus.OK));
    }

    @Test
    void aConfirmedOrderBecomesAPendingShipmentOnceEvenIfRedelivered() throws Exception {
        var event = OrderConfirmed.of(UUID.randomUUID(), "cust");
        confirm(event);
        confirm(event);   // redelivery
        awaitShipment(event.orderId());

        assertThat(shipmentOf(event.orderId()).get("status").asText()).isEqualTo("PENDING");
        var queue = http.exchange("/api/v1/shipments?status=PENDING&limit=200", HttpMethod.GET, new HttpEntity<>(headers("ADMIN")), JsonNode.class).getBody();
        long mine = 0;
        for (JsonNode s : queue) {
            if (s.get("orderId").asText().equals(event.orderId().toString())) {
                mine++;
            }
        }
        assertThat(mine).isEqualTo(1);
    }

    @Test
    void shippingThenDeliveringPublishesEachEventOnce() throws Exception {
        var event = OrderConfirmed.of(UUID.randomUUID(), "cust");
        String key = event.orderId().toString();
        try (var shipped = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.SHIPMENT_SHIPPED);
             var delivered = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.SHIPMENT_DELIVERED)) {
            confirm(event);
            awaitShipment(event.orderId());

            assertThat(post(event.orderId(), "deliver", Map.of())).as("not shipped yet").isEqualTo(HttpStatus.CONFLICT);
            assertThat(post(event.orderId(), "ship", Map.of("carrier", "DHL", "trackingNumber", "TRACK-1"))).isEqualTo(HttpStatus.OK);
            assertThat(post(event.orderId(), "ship", Map.of("carrier", "UPS", "trackingNumber", "TRACK-2"))).isEqualTo(HttpStatus.CONFLICT);

            var record = shipped.awaitKey(key, Duration.ofSeconds(30)).orElseThrow();
            var body = mapper.readValue(record.value(), OrderShipped.class);
            assertThat(body.carrier()).isEqualTo("DHL");
            assertThat(body.trackingNumber()).isEqualTo("TRACK-1");

            assertThat(post(event.orderId(), "deliver", Map.of())).isEqualTo(HttpStatus.OK);
            assertThat(mapper.readValue(delivered.awaitKey(key, Duration.ofSeconds(30)).orElseThrow().value(), OrderDelivered.class)
                    .orderId()).isEqualTo(event.orderId());

            assertThat(shipped.collect(key, Duration.ofSeconds(3))).hasSize(1);
            assertThat(delivered.collect(key, Duration.ofSeconds(3))).hasSize(1);
        }
        assertThat(shipmentOf(event.orderId()).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void cancellingBeforeDispatchStopsTheShipment() throws Exception {
        var event = OrderConfirmed.of(UUID.randomUUID(), "cust");
        confirm(event);
        awaitShipment(event.orderId());

        var cancel = OrderCancellationRequested.of(event.orderId(), "changed my mind");
        kafka.send(Topics.ORDER_CANCELLATION_REQUESTED, event.orderId().toString(), mapper.writeValueAsString(cancel)).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(shipmentOf(event.orderId()).get("status").asText()).isEqualTo("CANCELLED"));
        assertThat(post(event.orderId(), "ship", Map.of("carrier", "DHL", "trackingNumber", "1"))).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aCancellationThatOvertakesTheConfirmationWins() throws Exception {
        var orderId = UUID.randomUUID();
        var cancel = OrderCancellationRequested.of(orderId, "early");
        kafka.send(Topics.ORDER_CANCELLATION_REQUESTED, orderId.toString(), mapper.writeValueAsString(cancel)).get();
        awaitShipment(orderId);   // the tombstone

        confirm(OrderConfirmed.of(orderId, "cust"));
        Thread.sleep(3_000);      // give the late confirmation time to (wrongly) change something

        assertThat(shipmentOf(orderId).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void theApiIsForAdminsOnly() {
        var orderId = UUID.randomUUID();
        assertThat(http.getForEntity("/api/v1/shipments/order/" + orderId, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String role : new String[] {"CUSTOMER", "SERVICE"}) {
            assertThat(http.exchange("/api/v1/shipments/order/" + orderId, HttpMethod.GET, new HttpEntity<>(headers(role)), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
