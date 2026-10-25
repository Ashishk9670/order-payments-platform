package com.example.shop.shipping.messaging;

import com.example.shop.common.events.JsonEvents;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.Topics;
import com.example.shop.shipping.domain.ShippingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ShippingListener {

    private final ObjectMapper mapper;
    private final ShippingService shipping;

    public ShippingListener(ObjectMapper mapper, ShippingService shipping) {
        this.mapper = mapper;
        this.shipping = shipping;
    }

    @KafkaListener(topics = Topics.ORDER_CONFIRMED, groupId = "shipping-service")
    public void onOrderConfirmed(String json) {
        shipping.onOrderConfirmed(JsonEvents.read(mapper, json, OrderConfirmed.class));
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLATION_REQUESTED, groupId = "shipping-service")
    public void onCancellationRequested(String json) {
        shipping.onCancellationRequested(JsonEvents.read(mapper, json, OrderCancellationRequested.class));
    }
}
