package com.example.shop.notification;

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

/**
 * Reads topics from the beginning so a test can assert on events the service under test published. Everything polled is
 * buffered: waiting for one key must not throw away records of other keys that arrive in the same poll.
 */
class KafkaProbe implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

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

    private void pollOnce() {
        consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
    }

    private List<ConsumerRecord<String, String>> withKey(String key) {
        return seen.stream().filter(r -> key.equals(r.key())).toList();
    }

    /** All records with this key seen within the window (also lets tests assert "exactly once"). */
    List<ConsumerRecord<String, String>> collect(String key, Duration window) {
        long end = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < end) {
            pollOnce();
        }
        return withKey(key);
    }

    /** First record with this key (already buffered or arriving within the timeout), or empty. */
    Optional<ConsumerRecord<String, String>> awaitKey(String key, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        do {
            var found = withKey(key);
            if (!found.isEmpty()) {
                return Optional.of(found.get(0));
            }
            pollOnce();
        } while (System.nanoTime() < end);
        return Optional.empty();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(1));
    }
}
