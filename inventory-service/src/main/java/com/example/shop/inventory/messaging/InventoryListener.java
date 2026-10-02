package com.example.shop.inventory.messaging;

import com.example.shop.common.events.JsonEvents;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.Topics;
import com.example.shop.inventory.domain.ReservationService;
import com.example.shop.inventory.redis.RedisLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryListener {

    private final ObjectMapper mapper;
    private final ReservationService service;
    private final RedisLock locks;

    public InventoryListener(ObjectMapper mapper, ReservationService service, RedisLock locks) {
        this.mapper = mapper;
        this.service = service;
        this.locks = locks;
    }

    @KafkaListener(topics = Topics.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(String json) {
        OrderCreated event = JsonEvents.read(mapper, json, OrderCreated.class);
        locks.withLocks(event.items().stream().map(OrderCreated.Line::sku).toList(), () -> {
            service.reserve(event);
            return null;
        });
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = "inventory-service")
    public void onPaymentFailed(String json) {
        service.releaseFor(JsonEvents.read(mapper, json, PaymentFailed.class));
    }
}
