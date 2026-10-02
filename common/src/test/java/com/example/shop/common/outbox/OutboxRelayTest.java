package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxRepository repository;
    @Mock KafkaTemplate<String, String> kafka;

    private OutboxEvent event(String topic, String payload) {
        return new OutboxEvent(UUID.randomUUID(), "agg-1", topic, "T", payload);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sentEventsAreMarkedPublished() {
        var e1 = event("t1", "p1");
        var e2 = event("t2", "p2");
        when(repository.lockNextBatch(100)).thenReturn(List.of(e1, e2));
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        new OutboxRelay(repository, kafka).relay();

        verify(kafka).send("t1", "agg-1", "p1");
        verify(kafka).send("t2", "agg-1", "p2");
        assertThat(e1).extracting("publishedAt").isNotNull();
        assertThat(e2).extracting("publishedAt").isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void failureStopsTheBatchToPreserveOrdering() {
        var e1 = event("t1", "p1");
        var e2 = event("t2", "p2");
        when(repository.lockNextBatch(100)).thenReturn(List.of(e1, e2));
        when(kafka.send(eq("t1"), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new OutboxRelay(repository, kafka).relay();

        assertThat(e1).extracting("publishedAt").isNull();
        assertThat(e2).extracting("publishedAt").isNull();
        verify(kafka, never()).send(eq("t2"), any(String.class), any(String.class));
    }
}
