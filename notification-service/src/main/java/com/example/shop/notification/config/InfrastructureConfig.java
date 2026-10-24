package com.example.shop.notification.config;

import com.example.shop.common.kafka.KafkaCommonConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Kept out of the main class so web-layer slice tests do not try to boot Kafka. */
@Configuration
@Import(KafkaCommonConfig.class)
class InfrastructureConfig {
}
