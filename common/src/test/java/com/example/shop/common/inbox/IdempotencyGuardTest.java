package com.example.shop.common.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock ProcessedEventRepository repository;

    @Test
    void firstDeliveryIsProcessedAndRedeliveryIsSkipped() {
        var id = UUID.randomUUID();
        when(repository.insertIfAbsent(id)).thenReturn(1, 0);
        var guard = new IdempotencyGuard(repository);

        assertThat(guard.firstTime(id)).isTrue();
        assertThat(guard.firstTime(id)).isFalse();
    }
}
