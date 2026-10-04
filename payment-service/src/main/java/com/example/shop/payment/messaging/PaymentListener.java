package com.example.shop.payment.messaging;

import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.JsonEvents;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.Topics;
import com.example.shop.payment.domain.PaymentProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentListener {

    private final ObjectMapper mapper;
    private final PaymentProcessor processor;

    public PaymentListener(ObjectMapper mapper, PaymentProcessor processor) {
        this.mapper = mapper;
        this.processor = processor;
    }

    @KafkaListener(topics = Topics.INVENTORY_RESERVED, groupId = "payment-service")
    public void onInventoryReserved(String json) {
        processor.process(JsonEvents.read(mapper, json, InventoryReserved.class));
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLATION_REQUESTED, groupId = "payment-service")
    public void onCancellationRequested(String json) {
        processor.refund(JsonEvents.read(mapper, json, OrderCancellationRequested.class));
    }
}
