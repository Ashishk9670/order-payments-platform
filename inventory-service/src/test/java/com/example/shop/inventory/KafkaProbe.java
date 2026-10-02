package com.example.shop.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads topics from the beginning so a test can assert on events the service under test published. */
class KafkaProbe implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;

    KafkaProbe(String bootstrapServers, String... topics) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(p);
        consumer.subscribe(List.of(topics));
    }

    /** All records with this key seen within the window (also lets tests assert "exactly once"). */
    List<ConsumerRecord<String, String>> collect(String key, Duration window) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long end = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < end) {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
        }
        return found;
    }

    /** First record with this key, or empty after the timeout. */
    Optional<ConsumerRecord<String, String>> awaitKey(String key, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            for (var r : consumer.poll(Duration.ofMillis(200))) {
                if (key.equals(r.key())) {
                    return Optional.of(r);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(1));
    }
}
