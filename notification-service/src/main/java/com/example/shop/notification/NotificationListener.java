package com.example.shop.notification;

import com.example.shop.common.events.JsonEvents;
import com.example.shop.common.events.OrderCancelled;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Terminal consumer of the saga. It only logs (a real one would call an email/SMS provider). Logs reference ids only,
 * never customer contact data. Sending twice is harmless for a notification, so no inbox table is needed here.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final ObjectMapper mapper;

    public NotificationListener(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.ORDER_CONFIRMED, groupId = "notification-service")
    public void onConfirmed(String json) {
        var e = JsonEvents.read(mapper, json, OrderConfirmed.class);
        log.info("NOTIFY customer={} : order {} confirmed", e.customerId(), e.orderId());
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED, groupId = "notification-service")
    public void onCancelled(String json) {
        var e = JsonEvents.read(mapper, json, OrderCancelled.class);
        log.info("NOTIFY customer={} : order {} cancelled ({})", e.customerId(), e.orderId(), e.reason());
    }
}
