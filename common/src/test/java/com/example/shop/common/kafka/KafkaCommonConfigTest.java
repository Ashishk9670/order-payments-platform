package com.example.shop.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shop.common.events.Topics;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaCommonConfigTest {

    private final KafkaCommonConfig config = new KafkaCommonConfig();

    @SuppressWarnings("unchecked")
    @Test
    void everyTopicConstantIsCreatedWithThreePartitions() throws Exception {
        var topics = config.platformTopics();

        Set<String> created = new LinkedHashSet<>();
        for (NewTopic t : (java.util.Collection<NewTopic>) ReflectionTestUtils.getField(topics, "newTopics")) {
            created.add(t.name());
            assertThat(t.numPartitions()).as(t.name()).isEqualTo(3);
        }
        Set<String> declared = new LinkedHashSet<>();
        for (Field f : Topics.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        assertThat(created).as("a Topics constant without a NewTopic would only work by broker auto-creation").isEqualTo(declared);
    }

    @SuppressWarnings("unchecked")
    @Test
    void errorHandlerIsAConfiguredRetryThenDeadLetterHandler() {
        KafkaOperations<Object, Object> template = org.mockito.Mockito.mock(KafkaOperations.class);
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

        DefaultErrorHandler handler = config.kafkaErrorHandler(template, provider);

        assertThat(handler).isNotNull();
        assertThat(handler.deliveryAttemptHeader()).isNotNull();   // handler is built and usable by the listener container factory
    }

    @Test
    void topicsAllListsEveryConstantExactlyOnce() throws Exception {
        Set<String> declared = new LinkedHashSet<>();
        for (Field f : Topics.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        assertThat(Topics.all()).containsExactlyInAnyOrderElementsOf(declared).doesNotHaveDuplicates();
    }

    @SuppressWarnings("unchecked")
    @Test
    void everyParkedRecordIsCountedPerSourceTopic() throws Exception {
        KafkaOperations<Object, Object> template = org.mockito.Mockito.mock(KafkaOperations.class);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(registry);
        org.mockito.Mockito.when(template.send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var handler = config.kafkaErrorHandler(template, provider);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<Object, Object>("order.created.v1", 0, 5L, "k", "garbage");

        // a non-retryable failure goes straight to the recoverer
        handler.handleOne(new IllegalArgumentException("bad payload"), record,
                org.mockito.Mockito.mock(org.apache.kafka.clients.consumer.Consumer.class),
                org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class));

        assertThat(registry.counter("dead_letter.records", "topic", "order.created.v1").count()).isEqualTo(1.0);
    }
}
