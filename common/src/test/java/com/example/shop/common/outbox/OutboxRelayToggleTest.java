package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/** The polling relay must switch off when Debezium takes over, or every event would be published twice. */
class OutboxRelayToggleTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(TraceContextCodec.class, () -> mock(TraceContextCodec.class))
                .withUserConfiguration(OutboxRelay.class);
    }

    @Test
    void relayIsActiveByDefault() {
        runner().run(ctx -> assertThat(ctx).hasSingleBean(OutboxRelay.class));
    }

    @Test
    void relayIsActiveWhenExplicitlyEnabled() {
        runner().withPropertyValues("outbox.relay.enabled=true").run(ctx -> assertThat(ctx).hasSingleBean(OutboxRelay.class));
    }

    @Test
    void relayIsOffWhenDebeziumDoesThePublishing() {
        runner().withPropertyValues("outbox.relay.enabled=false").run(ctx -> assertThat(ctx).doesNotHaveBean(OutboxRelay.class));
    }
}
