package com.example.shop.notification.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class DeadLetterServiceTest {

    static final String DLT = "order.created.v1.DLT";
    static final String SOURCE = "order.created.v1";

    /** MockConsumer whose close() is a no-op, so one instance (and its committed offsets) survives several service calls. */
    static class ReusableConsumer extends MockConsumer<String, String> {
        ReusableConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override public synchronized void close() { }

        @Override public synchronized void close(Duration timeout) { }
    }

    ReusableConsumer consumer = new ReusableConsumer();
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    DeadLetterService service;
    final TopicPartition tp = new TopicPartition(DLT, 0);

    @BeforeEach
    void setUp() {
        ConsumerFactory<String, String> factory = mock(ConsumerFactory.class);
        when(factory.createConsumer(any(), any())).thenReturn(consumer);
        service = new DeadLetterService(factory, kafka, meters);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        consumer.updatePartitions(DLT, List.of(new PartitionInfo(DLT, 0, null, null, null)));
        consumer.updatePartitions("unrelated.topic", List.of(new PartitionInfo("unrelated.topic", 0, null, null, null)));
        consumer.updatePartitions("not.ours.v9.DLT", List.of(new PartitionInfo("not.ours.v9.DLT", 0, null, null, null)));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.assign(List.of(tp));
    }

    private void park(long offset, String key, String source, String error) {
        var headers = new RecordHeaders();
        if (source != null) {
            headers.add("kafka_dlt-original-topic", source.getBytes(StandardCharsets.UTF_8));
        }
        if (error != null) {
            headers.add("kafka_dlt-exception-message", error.getBytes(StandardCharsets.UTF_8));
        }
        consumer.addRecord(new ConsumerRecord<>(DLT, 0, offset, 1000L + offset, TimestampType.CREATE_TIME, 0, 0, key, "payload-" + key,
                headers, Optional.empty()));
    }

    private void endAt(long end) {
        consumer.updateEndOffsets(Map.of(tp, end));
    }

    @Test
    void listsOnlyThePlatformsDeadLetterTopicsWithTheirPendingCount() {
        park(0, "a", SOURCE, "boom");
        park(1, "b", SOURCE, "boom");
        endAt(2);

        var topics = service.list();

        assertThat(topics).containsExactly(new DeadLetterService.DeadLetterTopic(DLT, SOURCE, 2));
    }

    @Test
    void peekShowsWhyEachRecordFailedAndCommitsNothing() {
        park(0, "a", SOURCE, "Cannot deserialize OrderCreated");
        park(1, "b", SOURCE, "other");
        endAt(2);

        var records = service.peek(DLT, 10);

        assertThat(records).hasSize(2);
        assertThat(records.get(0)).satisfies(r -> {
            assertThat(r.key()).isEqualTo("a");
            assertThat(r.sourceTopic()).isEqualTo(SOURCE);
            assertThat(r.error()).isEqualTo("Cannot deserialize OrderCreated");
            assertThat(r.payload()).isEqualTo("payload-a");
        });
        assertThat(service.list().get(0).pending()).isEqualTo(2);   // still pending: peeking is read-only
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void replaySendsEachRecordBackToItsSourceTopicWithTheSameKeyAndMarksItDone() {
        park(0, "a", SOURCE, "boom");
        park(1, "b", SOURCE, "boom");
        endAt(2);

        var result = service.replay(DLT, 10);

        assertThat(result).isEqualTo(new DeadLetterService.ReplayResult(DLT, 2, 0, 0));
        var sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::topic).containsOnly(SOURCE);
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsExactly("a", "b");
        assertThat(sent.getAllValues()).extracting(ProducerRecord::value).containsExactly("payload-a", "payload-b");
        assertThat(meters.counter("dead_letter.replayed", "topic", SOURCE).count()).isEqualTo(2.0);
    }

    @Test
    void aSecondReplayDoesNotResendWhatWasAlreadyReplayed() {
        park(0, "a", SOURCE, "boom");
        endAt(1);
        service.replay(DLT, 10);

        var again = service.replay(DLT, 10);

        assertThat(again.replayed()).isZero();
        verify(kafka, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void replayHonoursTheMaxAndLeavesTheRestPending() {
        for (int i = 0; i < 5; i++) {
            park(i, "k" + i, SOURCE, "boom");
        }
        endAt(5);

        var first = service.replay(DLT, 2);

        assertThat(first.replayed()).isEqualTo(2);
        assertThat(first.pending()).isEqualTo(3);
        // MockConsumer discards what it already handed out (a real broker serves the log again after the service seeks back)
        for (int i = 2; i < 5; i++) {
            park(i, "k" + i, SOURCE, "boom");
        }
        var rest = service.replay(DLT, 10);
        assertThat(rest.replayed()).isEqualTo(3);
        assertThat(rest.pending()).isZero();
    }

    @Test
    void aRecordWithoutAValidSourceTopicIsSkippedNotSentAnywhere() {
        park(0, "no-header", null, "boom");
        park(1, "evil", "internal.secrets", "boom");     // not a platform topic: refuse to publish there
        park(2, "good", SOURCE, "boom");
        endAt(3);

        var result = service.replay(DLT, 10);

        assertThat(result).isEqualTo(new DeadLetterService.ReplayResult(DLT, 1, 2, 0));
        var sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().key()).isEqualTo("good");
    }

    @Test
    void replayStopsAtTheFirstRecordThatCannotBeSentAndKeepsEverythingAfterItPending() {
        park(0, "a", SOURCE, "boom");
        park(1, "b", SOURCE, "boom");
        park(2, "c", SOURCE, "boom");
        endAt(3);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        var result = service.replay(DLT, 10);

        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.pending()).isEqualTo(2);    // b and c: nothing is skipped past a failure, order is preserved
    }

    @Test
    void acknowledgeDropsEverythingPendingWithoutReplayingIt() {
        park(0, "a", SOURCE, "boom");
        park(1, "b", SOURCE, "boom");
        endAt(2);

        long skipped = service.acknowledge(DLT);

        assertThat(skipped).isEqualTo(2);
        assertThat(service.list().get(0).pending()).isZero();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void onlyPlatformDeadLetterTopicsAreAcceptedAsTargets() {
        assertThat(DeadLetterService.isKnownDeadLetterTopic("order.created.v1.DLT")).isTrue();
        assertThat(DeadLetterService.isKnownDeadLetterTopic("payment.refunded.v1.DLT")).isTrue();
        assertThat(DeadLetterService.isKnownDeadLetterTopic("order.created.v1")).isFalse();
        assertThat(DeadLetterService.isKnownDeadLetterTopic("anything.else.DLT")).isFalse();
        assertThat(DeadLetterService.isKnownDeadLetterTopic("__consumer_offsets")).isFalse();
        assertThat(DeadLetterService.isKnownDeadLetterTopic(null)).isFalse();
    }
}
