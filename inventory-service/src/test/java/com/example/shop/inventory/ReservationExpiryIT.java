package com.example.shop.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.InventoryExpired;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.example.shop.inventory.domain.InventoryItem;
import com.example.shop.inventory.domain.InventoryItemRepository;
import com.example.shop.inventory.domain.Reservation;
import com.example.shop.inventory.domain.ReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/** Unpaid reservations expire and tell the order service; paid ones never do; refunds give paid stock back. */
@SpringBootTest(properties = {
        "inventory.reservation-ttl=PT3S",
        "inventory.expiry.poll-interval-ms=500",
        "security.jwt.secret=integration-test-secret-integration-test-0123456789"})
@Import(Containers.class)
class ReservationExpiryIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired InventoryItemRepository items;
    @Autowired ReservationRepository reservations;
    @Autowired ObjectMapper mapper;

    private String sku(int stock) {
        String sku = "EXP-" + UUID.randomUUID();
        items.save(new InventoryItem(sku, stock));
        return sku;
    }

    private void send(String topic, UUID orderId, Object event) throws Exception {
        kafka.send(topic, orderId.toString(), mapper.writeValueAsString(event)).get();
    }

    private OrderCreated reserve(String sku, int qty) throws Exception {
        var order = OrderCreated.of(UUID.randomUUID(), "cust", List.of(new OrderCreated.Line(sku, qty)), new BigDecimal("10.00"));
        send(Topics.ORDER_CREATED, order.orderId(), order);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(reservations.findByOrderIdAndStatus(order.orderId(), Reservation.Status.RESERVED)).hasSize(1));
        return order;
    }

    @Test
    void anUnpaidReservationExpiresReleasesTheStockAndTellsTheOrderService() throws Exception {
        String sku = sku(10);
        String other = sku(5);   // a two-line order: every line must be released AND marked, not just the first
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.INVENTORY_EXPIRED)) {
            var order = OrderCreated.of(UUID.randomUUID(), "cust",
                    List.of(new OrderCreated.Line(sku, 4), new OrderCreated.Line(other, 2)), new BigDecimal("10.00"));
            send(Topics.ORDER_CREATED, order.orderId(), order);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(reservations.findByOrderIdAndStatus(order.orderId(), Reservation.Status.RESERVED)).hasSize(2));
            assertThat(items.findById(sku).orElseThrow().getAvailable()).isEqualTo(6);

            var record = probe.awaitKey(order.orderId().toString(), Duration.ofSeconds(60)).orElseThrow();
            assertThat(mapper.readValue(record.value(), InventoryExpired.class).reason()).contains("PT3S");
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var item = items.findById(sku).orElseThrow();
                assertThat(item.getAvailable()).isEqualTo(10);
                assertThat(item.getReserved()).isZero();
            });
            assertThat(items.findById(other).orElseThrow().getAvailable()).isEqualTo(5);
            assertThat(reservations.findByOrderIdAndStatus(order.orderId(), Reservation.Status.EXPIRED)).hasSize(2);
            // exactly one expiry event, even though the job keeps running
            assertThat(probe.collect(order.orderId().toString(), Duration.ofSeconds(4))).hasSize(1);
        }
    }

    @Test
    void aPaidReservationIsNeverExpiredAndARefundStillRestocksIt() throws Exception {
        String sku = sku(10);
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), Topics.INVENTORY_EXPIRED)) {
            var order = reserve(sku, 3);
            send(Topics.PAYMENT_AUTHORIZED, order.orderId(), PaymentAuthorized.of(order.orderId(), UUID.randomUUID()));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(reservations.findByOrderIdAndStatus(order.orderId(), Reservation.Status.COMMITTED)).hasSize(1));

            // well past the 3 s TTL plus several job passes: a sale must be left alone
            assertThat(probe.collect(order.orderId().toString(), Duration.ofSeconds(8))).isEmpty();
            var item = items.findById(sku).orElseThrow();
            assertThat(item.getAvailable()).isEqualTo(7);
            assertThat(item.getReserved()).isEqualTo(3);

            send(Topics.PAYMENT_REFUNDED, order.orderId(), PaymentRefunded.of(order.orderId(), UUID.randomUUID()));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var after = items.findById(sku).orElseThrow();
                assertThat(after.getAvailable()).isEqualTo(10);
                assertThat(after.getReserved()).isZero();
            });
        }
    }
}
