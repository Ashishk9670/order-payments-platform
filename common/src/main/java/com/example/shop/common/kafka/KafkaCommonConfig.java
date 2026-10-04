package com.example.shop.common.kafka;

import com.example.shop.common.events.Topics;
import java.util.stream.Stream;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaCommonConfig {

    /** Retry with backoff (1s, 2s, 4s ...) then park the record on "<topic>.DLT" so the partition is never blocked. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        // partition -1: let the producer pick, so the DLT needs no matching partition count
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(15_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public KafkaAdmin.NewTopics platformTopics() {
        return new KafkaAdmin.NewTopics(Stream.of(
                        Topics.ORDER_CREATED, Topics.ORDER_CONFIRMED, Topics.ORDER_CANCELLED,
                        Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED,
                        Topics.PAYMENT_AUTHORIZED, Topics.PAYMENT_FAILED,
                        Topics.ORDER_CANCELLATION_REQUESTED, Topics.PAYMENT_REFUNDED, Topics.PAYMENT_REFUND_FAILED)
                .map(name -> TopicBuilder.name(name).partitions(3).replicas(1).build())
                .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
    }
}
