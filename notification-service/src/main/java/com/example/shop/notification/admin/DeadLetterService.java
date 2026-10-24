package com.example.shop.notification.admin;

import com.example.shop.common.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator tooling for dead-letter topics (<code>&lt;topic&gt;.DLT</code>): see what was parked, send it back to the topic it came
 * from once the cause is fixed, or acknowledge records that can never succeed.
 *
 * <p>Progress is tracked as the committed offsets of the consumer group {@value #GROUP}, so a replay continues where the last one
 * stopped and a record is never replayed twice by accident. Nothing is ever deleted from the DLT itself (it is a log).
 * Only the known platform topics are valid targets, so this cannot be used to inject messages anywhere else.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    static final String GROUP = "dlt-admin";
    static final String SUFFIX = ".DLT";
    static final String HEADER_SOURCE_TOPIC = "kafka_dlt-original-topic";
    static final String HEADER_ERROR = "kafka_dlt-exception-message";

    public record DeadLetterTopic(String topic, String sourceTopic, long pending) {
    }

    public record DeadLetterRecord(int partition, long offset, String key, long timestamp, String sourceTopic, String error, String payload) {
    }

    public record ReplayResult(String topic, int replayed, int skipped, long pending) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry meters;
    private final ReentrantLock lock = new ReentrantLock();   // one replay at a time per instance

    public DeadLetterService(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.consumers = consumers;
        this.kafka = kafka;
        this.meters = meters;
    }

    public static boolean isKnownDeadLetterTopic(String topic) {
        return topic != null && topic.endsWith(SUFFIX) && Topics.all().contains(topic.substring(0, topic.length() - SUFFIX.length()));
    }

    /** Dead-letter topics that exist, with how many records still wait for a decision. */
    public List<DeadLetterTopic> list() {
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "list")) {
            List<DeadLetterTopic> out = new ArrayList<>();
            for (String topic : new TreeMap<>(consumer.listTopics()).keySet()) {
                if (isKnownDeadLetterTopic(topic)) {
                    out.add(new DeadLetterTopic(topic, sourceOf(topic), pendingOffsets(consumer, partitionsOf(consumer, topic)).values().stream()
                            .mapToLong(o -> o[1] - o[0]).sum()));
                }
            }
            return out;
        }
    }

    /** The oldest pending records, with why each one failed. */
    public List<DeadLetterRecord> peek(String topic, int limit) {
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "peek")) {
            var partitions = partitionsOf(consumer, topic);
            var starts = positionAtPendingStart(consumer, partitions);
            List<DeadLetterRecord> out = new ArrayList<>();
            for (ConsumerRecord<String, String> record : drain(consumer, starts, limit)) {
                out.add(toView(record));
            }
            return out;
        }
    }

    /** Sends up to {@code max} pending records back to the topic they came from; progress is committed per replayed record. */
    public ReplayResult replay(String topic, int max) {
        lock.lock();
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "replay")) {
            var partitions = partitionsOf(consumer, topic);
            var starts = positionAtPendingStart(consumer, partitions);
            Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
            int replayed = 0;
            int skipped = 0;
            for (ConsumerRecord<String, String> record : drain(consumer, starts, max)) {
                String source = headerValue(record, HEADER_SOURCE_TOPIC);
                if (source == null || !Topics.all().contains(source)) {
                    log.warn("Skipping {}-{}@{}: no valid source topic ({})", record.topic(), record.partition(), record.offset(), source);
                    skipped++;
                } else {
                    try {
                        kafka.send(new ProducerRecord<>(source, record.key(), record.value())).get(10, TimeUnit.SECONDS);
                        replayed++;
                        meters.counter("dead_letter.replayed", "topic", source).increment();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.warn("Replay stopped at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.toString());
                        break;   // keep order and do not skip past a record that could not be sent
                    }
                }
                done.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
            }
            if (!done.isEmpty()) {
                consumer.commitSync(done);
            }
            return new ReplayResult(topic, replayed, skipped, pendingOffsets(consumer, partitions).values().stream().mapToLong(o -> o[1] - o[0]).sum());
        } finally {
            lock.unlock();
        }
    }

    /** Marks everything currently parked as handled WITHOUT replaying it (for records that can never succeed). */
    public long acknowledge(String topic) {
        lock.lock();
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "ack")) {
            var partitions = partitionsOf(consumer, topic);
            var offsets = pendingOffsets(consumer, partitions);
            Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
            long skipped = 0;
            for (var e : offsets.entrySet()) {
                skipped += e.getValue()[1] - e.getValue()[0];
                commit.put(e.getKey(), new OffsetAndMetadata(e.getValue()[1]));
            }
            if (!commit.isEmpty()) {
                consumer.commitSync(commit);
            }
            log.warn("Acknowledged {} dead-lettered record(s) on {} without replaying them", skipped, topic);
            return skipped;
        } finally {
            lock.unlock();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static String sourceOf(String dltTopic) {
        return dltTopic.substring(0, dltTopic.length() - SUFFIX.length());
    }

    private static Set<TopicPartition> partitionsOf(Consumer<String, String> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return Set.of();
        }
        return infos.stream().map(p -> new TopicPartition(p.topic(), p.partition())).collect(Collectors.toSet());
    }

    /** partition -> {first pending offset, end offset}. The first pending offset is the group's committed one (or the start). */
    private static Map<TopicPartition, long[]> pendingOffsets(Consumer<String, String> consumer, Set<TopicPartition> partitions) {
        Map<TopicPartition, long[]> out = new LinkedHashMap<>();
        if (partitions.isEmpty()) {
            return out;
        }
        var begin = consumer.beginningOffsets(partitions);
        var end = consumer.endOffsets(partitions);
        var committed = consumer.committed(partitions);
        for (TopicPartition tp : partitions) {
            OffsetAndMetadata c = committed == null ? null : committed.get(tp);
            long start = Math.max(c == null ? begin.get(tp) : c.offset(), begin.get(tp));
            out.put(tp, new long[] {start, end.get(tp)});
        }
        return out;
    }

    private static Map<TopicPartition, Long> positionAtPendingStart(Consumer<String, String> consumer, Set<TopicPartition> partitions) {
        Map<TopicPartition, Long> starts = new LinkedHashMap<>();
        pendingOffsets(consumer, partitions).forEach((tp, o) -> starts.put(tp, o[0]));
        consumer.assign(starts.keySet());
        starts.forEach(consumer::seek);
        return starts;
    }

    /** Reads up to {@code max} records starting at each partition's pending offset, oldest first within a partition. */
    private static List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, Map<TopicPartition, Long> starts, int max) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        int emptyPolls = 0;
        while (out.size() < max && emptyPolls < 2) {
            var polled = consumer.poll(Duration.ofMillis(500));
            if (polled.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (ConsumerRecord<String, String> record : polled) {
                if (out.size() < max) {
                    out.add(record);
                }
            }
        }
        return out;
    }

    private static DeadLetterRecord toView(ConsumerRecord<String, String> r) {
        return new DeadLetterRecord(r.partition(), r.offset(), r.key(), r.timestamp(), headerValue(r, HEADER_SOURCE_TOPIC),
                headerValue(r, HEADER_ERROR), r.value());
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
