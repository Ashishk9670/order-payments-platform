package com.example.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=integration-test-secret-integration-test-0123456789")
@Import(Containers.class)
class PaymentFlowIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder jwtEncoder;

    private HttpEntity<Void> as(String role) {
        var now = java.time.Instant.now();
        var claims = JwtClaimsSet.builder().subject("it").issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", java.util.List.of(role)).build();
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        return new HttpEntity<>(headers);
    }

    private JsonNode paymentOf(Object orderId) {
        return http.exchange("/api/v1/payments/order/" + orderId, HttpMethod.GET, as("ADMIN"), JsonNode.class).getBody();
    }

    private void send(InventoryReserved e) throws Exception {
        kafka.send(Topics.INVENTORY_RESERVED, e.orderId().toString(), mapper.writeValueAsString(e)).get();
    }

    @Test
    void authorizesNormalAmountAndExposesPaymentOverRest() throws Exception {
        var event = InventoryReserved.of(UUID.randomUUID(), "cust", new BigDecimal("42.00"));
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_AUTHORIZED)) {
            send(event);
            assertThat(probe.awaitKey(event.orderId().toString(), Duration.ofSeconds(20))).isPresent();
        }
        JsonNode payment = paymentOf(event.orderId());
        assertThat(payment.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("42.00");
    }

    @Test
    void declinesAmountAboveLimitAndEmitsPaymentFailed() throws Exception {
        var event = InventoryReserved.of(UUID.randomUUID(), "cust", new BigDecimal("20000.00"));
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_FAILED)) {
            send(event);
            var record = probe.awaitKey(event.orderId().toString(), Duration.ofSeconds(20)).orElseThrow();
            assertThat(mapper.readValue(record.value(), PaymentFailed.class).reason()).contains("limit");
        }
        JsonNode payment = paymentOf(event.orderId());
        assertThat(payment.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void redeliveredEventCreatesOnlyOnePayment() throws Exception {
        var event = InventoryReserved.of(UUID.randomUUID(), "cust", new BigDecimal("10.00"));
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_AUTHORIZED)) {
            send(event);
            send(event);
            assertThat(probe.collect(event.orderId().toString(), Duration.ofSeconds(8))).hasSize(1);
        }
    }

    @Test
    void unknownOrderHasNoPayment() {
        var response = http.exchange("/api/v1/payments/order/" + UUID.randomUUID(), HttpMethod.GET, as("ADMIN"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theApiRequiresAnAdminOrServiceToken() {
        var orderId = UUID.randomUUID();

        assertThat(http.getForEntity("/api/v1/payments/order/" + orderId, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/payments/order/" + orderId, HttpMethod.GET, as("CUSTOMER"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/api/v1/payments/order/" + orderId, HttpMethod.GET, as("SERVICE"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);   // allowed in: it just has no payment
    }

    @Test
    void cancellationRequestRefundsTheAuthorizedPaymentExactlyOnce() throws Exception {
        var reserved = InventoryReserved.of(UUID.randomUUID(), "cust", new BigDecimal("42.00"));
        String key = reserved.orderId().toString();
        try (var authorized = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_AUTHORIZED);
             var refunded = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_REFUNDED)) {
            send(reserved);
            assertThat(authorized.awaitKey(key, Duration.ofSeconds(30))).isPresent();

            var cancel = OrderCancellationRequested.of(reserved.orderId(), "changed my mind");
            kafka.send(Topics.ORDER_CANCELLATION_REQUESTED, key, mapper.writeValueAsString(cancel)).get();
            kafka.send(Topics.ORDER_CANCELLATION_REQUESTED, key, mapper.writeValueAsString(cancel)).get();   // redelivery

            assertThat(refunded.collect(key, Duration.ofSeconds(10))).hasSize(1);
        }
        JsonNode payment = paymentOf(reserved.orderId());
        assertThat(payment.get("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    void cancellationOfAnOrderThatWasNeverChargedIsRefusedNotInvented() throws Exception {
        var orderId = UUID.randomUUID();
        try (var failed = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.PAYMENT_REFUND_FAILED)) {
            var cancel = OrderCancellationRequested.of(orderId, "x");
            kafka.send(Topics.ORDER_CANCELLATION_REQUESTED, orderId.toString(), mapper.writeValueAsString(cancel)).get();

            var record = failed.awaitKey(orderId.toString(), Duration.ofSeconds(30)).orElseThrow();
            assertThat(mapper.readValue(record.value(), PaymentRefundFailed.class).reason()).contains("No authorized payment");
        }
    }
}
