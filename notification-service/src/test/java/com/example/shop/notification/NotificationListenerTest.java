package com.example.shop.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shop.common.events.OrderCancelled;
import com.example.shop.common.events.OrderConfirmed;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationListener listener = new NotificationListener(mapper);

    @Test
    void confirmedAndCancelledNotificationsAreHandled() throws Exception {
        var confirmed = OrderConfirmed.of(UUID.randomUUID(), "cust-1");
        var cancelled = OrderCancelled.of(UUID.randomUUID(), "cust-1", "payment declined");

        assertThatCode(() -> listener.onConfirmed(mapper.writeValueAsString(confirmed))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onCancelled(mapper.writeValueAsString(cancelled))).doesNotThrowAnyException();
    }

    @Test
    void malformedMessagesAreNonRetryableSoTheyGoToTheDeadLetterTopic() {
        assertThatThrownBy(() -> listener.onConfirmed("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onCancelled("{")).isInstanceOf(IllegalArgumentException.class);
    }
}
