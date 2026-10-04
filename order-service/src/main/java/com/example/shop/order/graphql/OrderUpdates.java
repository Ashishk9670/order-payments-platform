package com.example.shop.order.graphql;

import com.example.shop.order.api.OrderChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Cross-instance fan-out for GraphQL subscriptions. The instance that commits a status change publishes the order id
 * on a Redis channel; EVERY instance receives it and pushes to its own connected subscribers. Without Redis, a
 * subscriber connected to instance A would never see a change handled by instance B.
 */
@Component
public class OrderUpdates implements MessageListener {

    public static final String CHANNEL = "order-updates";
    private static final Logger log = LoggerFactory.getLogger(OrderUpdates.class);

    private final StringRedisTemplate redis;
    private final Sinks.Many<UUID> sink = Sinks.many().multicast().directBestEffort();

    public OrderUpdates(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** After commit, so a subscriber that reloads the order always sees the new state. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publish(OrderChanged event) {
        try {
            redis.convertAndSend(CHANNEL, event.orderId().toString());
        } catch (RuntimeException e) {
            log.warn("Could not publish order update for {}: {}", event.orderId(), e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            sink.tryEmitNext(UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed order update message");
        }
    }

    public Flux<UUID> changesOf(UUID orderId) {
        return sink.asFlux().filter(orderId::equals);
    }
}
