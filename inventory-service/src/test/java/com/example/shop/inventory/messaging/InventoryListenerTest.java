package com.example.shop.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.inventory.domain.ReservationService;
import com.example.shop.inventory.redis.RedisLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryListenerTest {

    @Mock ReservationService service;
    @Mock RedisLock locks;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    InventoryListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryListener(mapper, service, locks);
    }

    @SuppressWarnings("unchecked")
    @Test
    void orderCreatedIsProcessedWhileHoldingALockOnEverySkuOfTheOrder() throws Exception {
        when(locks.withLocks(any(), any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(1)).get());
        var event = OrderCreated.of(UUID.randomUUID(), "c",
                List.of(new OrderCreated.Line("A", 1), new OrderCreated.Line("B", 2)), BigDecimal.TEN);

        listener.onOrderCreated(mapper.writeValueAsString(event));

        ArgumentCaptor<Collection<String>> skus = ArgumentCaptor.forClass(Collection.class);
        verify(locks).withLocks(skus.capture(), any());
        assertThat(skus.getValue()).containsExactly("A", "B");
        verify(service).reserve(event);
    }

    @Test
    void paymentFailureAndRefundBothReleaseStock() throws Exception {
        var failed = PaymentFailed.of(UUID.randomUUID(), "declined");
        var refunded = PaymentRefunded.of(UUID.randomUUID(), UUID.randomUUID());

        listener.onPaymentFailed(mapper.writeValueAsString(failed));
        listener.onPaymentRefunded(mapper.writeValueAsString(refunded));

        verify(service).releaseFor(failed);
        verify(service).releaseFor(refunded);
    }

    @Test
    void malformedMessagesAreNonRetryable() {
        assertThatThrownBy(() -> listener.onOrderCreated("{nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onPaymentFailed("{nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onPaymentRefunded("{nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
