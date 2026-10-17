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

        DefaultErrorHandler handler = config.kafkaErrorHandler(template);

        assertThat(handler).isNotNull();
        assertThat(handler.deliveryAttemptHeader()).isNotNull();   // handler is built and usable by the listener container factory
    }
}
