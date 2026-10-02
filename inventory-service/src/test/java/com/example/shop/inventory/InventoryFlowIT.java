package com.example.shop.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.Topics;
import com.example.shop.inventory.domain.InventoryItem;
import com.example.shop.inventory.domain.InventoryItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(properties = "spring.kafka.listener.concurrency=3")
@Import(Containers.class)
class InventoryFlowIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired InventoryItemRepository items;
    @Autowired ObjectMapper mapper;

    private String sku(int stock) {
        String sku = "IT-" + UUID.randomUUID();
        items.save(new InventoryItem(sku, stock));
        return sku;
    }

    private OrderCreated order(OrderCreated.Line... lines) {
        return OrderCreated.of(UUID.randomUUID(), "cust", List.of(lines), new BigDecimal("10.00"));
    }

    private void send(String topic, String key, Object event) throws Exception {
        kafka.send(topic, key, mapper.writeValueAsString(event)).get();
    }

    private String bootstrap() {
        return kafkaDetails.getBootstrapServers().get(0);
    }

    @Test
    void reservesStockAndEmitsInventoryReserved() throws Exception {
        String sku = sku(10);
        var order = order(new OrderCreated.Line(sku, 3));
        try (var probe = new KafkaProbe(bootstrap(), Topics.INVENTORY_RESERVED)) {
            send(Topics.ORDER_CREATED, order.orderId().toString(), order);

            var record = probe.awaitKey(order.orderId().toString(), Duration.ofSeconds(20)).orElseThrow();
            assertThat(mapper.readValue(record.value(), InventoryReserved.class).totalAmount()).isEqualByComparingTo("10.00");
        }
        var item = items.findById(sku).orElseThrow();
        assertThat(item.getAvailable()).isEqualTo(7);
        assertThat(item.getReserved()).isEqualTo(3);
    }

    @Test
    void insufficientStockEmitsFailedAndLeavesStockUntouched() throws Exception {
        String sku = sku(2);
        var order = order(new OrderCreated.Line(sku, 5));
        try (var probe = new KafkaProbe(bootstrap(), Topics.INVENTORY_FAILED)) {
            send(Topics.ORDER_CREATED, order.orderId().toString(), order);

            var record = probe.awaitKey(order.orderId().toString(), Duration.ofSeconds(20)).orElseThrow();
            assertThat(mapper.readValue(record.value(), InventoryFailed.class).reason()).contains(sku);
        }
        assertThat(items.findById(sku).orElseThrow().getAvailable()).isEqualTo(2);
    }

    @Test
    void multiLineOrderIsAllOrNothing() throws Exception {
        String ok = sku(10);
        String scarce = sku(1);
        var order = order(new OrderCreated.Line(ok, 4), new OrderCreated.Line(scarce, 9));
        try (var probe = new KafkaProbe(bootstrap(), Topics.INVENTORY_FAILED)) {
            send(Topics.ORDER_CREATED, order.orderId().toString(), order);
            assertThat(probe.awaitKey(order.orderId().toString(), Duration.ofSeconds(20))).isPresent();
        }
        assertThat(items.findById(ok).orElseThrow().getAvailable()).isEqualTo(10);
        assertThat(items.findById(ok).orElseThrow().getReserved()).isZero();
    }

    @Test
    void redeliveredOrderCreatedIsProcessedExactlyOnce() throws Exception {
        String sku = sku(10);
        var order = order(new OrderCreated.Line(sku, 3));
        try (var probe = new KafkaProbe(bootstrap(), Topics.INVENTORY_RESERVED)) {
            send(Topics.ORDER_CREATED, order.orderId().toString(), order);
            send(Topics.ORDER_CREATED, order.orderId().toString(), order);

            var seen = probe.collect(order.orderId().toString(), Duration.ofSeconds(8));
            assertThat(seen).hasSize(1);
        }
        assertThat(items.findById(sku).orElseThrow().getAvailable()).isEqualTo(7);
    }

    @Test
    void paymentFailureReleasesTheReservation() throws Exception {
        String sku = sku(10);
        var order = order(new OrderCreated.Line(sku, 4));
        send(Topics.ORDER_CREATED, order.orderId().toString(), order);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(
                () -> assertThat(items.findById(sku).orElseThrow().getAvailable()).isEqualTo(6));

        send(Topics.PAYMENT_FAILED, order.orderId().toString(), PaymentFailed.of(order.orderId(), "declined"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var item = items.findById(sku).orElseThrow();
            assertThat(item.getAvailable()).isEqualTo(10);
            assertThat(item.getReserved()).isZero();
        });
    }

    @Test
    void neverOversellsUnderConcurrentOrders() throws Exception {
        String sku = sku(5);
        List<OrderCreated> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orders.add(order(new OrderCreated.Line(sku, 1)));
        }
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (var probe = new KafkaProbe(bootstrap(), Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED)) {
            orders.parallelStream().forEach(o -> {
                try {
                    send(Topics.ORDER_CREATED, o.orderId().toString(), o);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            for (var o : orders) {
                var rec = probe.awaitKey(o.orderId().toString(), Duration.ofSeconds(30)).orElseThrow();
                (rec.topic().equals(Topics.INVENTORY_RESERVED) ? reserved : failed).incrementAndGet();
            }
        }

        assertThat(reserved.get()).isEqualTo(5);
        assertThat(failed.get()).isEqualTo(15);
        var item = items.findById(sku).orElseThrow();
        assertThat(item.getAvailable()).isZero();
        assertThat(item.getReserved()).isEqualTo(5);
    }
}
