package com.example.shop.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock OutboxRepository repository;

    @Test
    void storesTheEventKeyedByOrderIdWithTopicTypeAndJsonPayload() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var event = OrderConfirmed.of(UUID.randomUUID(), "cust-1");

        new OutboxService(repository, mapper).publish(Topics.ORDER_CONFIRMED, event);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(event.eventId());           // the outbox row id IS the event id (consumer idempotency)
        assertThat(saved.getAggregateId()).isEqualTo(event.orderId().toString());   // Kafka key: per-order ordering
        assertThat(saved.getTopic()).isEqualTo(Topics.ORDER_CONFIRMED);
        assertThat(saved.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(mapper.readValue(saved.getPayload(), OrderConfirmed.class)).isEqualTo(event);
    }
}
