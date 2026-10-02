package com.example.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.PaymentFailed;
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
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Containers.class)
class PaymentFlowIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;

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
        JsonNode payment = http.getForObject("/api/v1/payments/order/" + event.orderId(), JsonNode.class);
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
        JsonNode payment = http.getForObject("/api/v1/payments/order/" + event.orderId(), JsonNode.class);
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
        var response = http.getForEntity("/api/v1/payments/order/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
