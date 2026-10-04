package com.example.shop.order.graphql;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class OrderUpdatesConfig {

    @Bean
    RedisMessageListenerContainer orderUpdatesContainer(RedisConnectionFactory factory, OrderUpdates updates) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(updates, new ChannelTopic(OrderUpdates.CHANNEL));
        return container;
    }
}
