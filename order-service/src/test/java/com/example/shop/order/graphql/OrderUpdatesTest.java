package com.example.shop.order.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.shop.order.api.OrderChanged;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class OrderUpdatesTest {

    @Mock StringRedisTemplate redis;

    private static DefaultMessage message(String body) {
        return new DefaultMessage(OrderUpdates.CHANNEL.getBytes(), body.getBytes());
    }

    @Test
    void committedChangeIsPublishedOnTheSharedChannel() {
        var id = UUID.randomUUID();
        new OrderUpdates(redis).publish(new OrderChanged(id));
        verify(redis).convertAndSend(OrderUpdates.CHANNEL, id.toString());
    }

    @Test
    void redisOutageDoesNotBreakTheBusinessTransactionCallback() {
        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        assertThatCode(() -> new OrderUpdates(redis).publish(new OrderChanged(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void receivedMessageReachesOnlySubscribersOfThatOrder() throws Exception {
        var updates = new OrderUpdates(redis);
        var mine = UUID.randomUUID();
        var future = updates.changesOf(mine).next().toFuture();

        updates.onMessage(message(UUID.randomUUID().toString()), null);   // someone else's order
        updates.onMessage(message(mine.toString()), null);

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(mine);
    }

    @Test
    void malformedMessageIsIgnored() {
        assertThatCode(() -> new OrderUpdates(redis).onMessage(message("not-a-uuid"), null)).doesNotThrowAnyException();
    }
}
