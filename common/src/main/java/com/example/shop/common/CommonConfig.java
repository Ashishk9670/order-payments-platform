package com.example.shop.common;

import com.example.shop.common.kafka.KafkaCommonConfig;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Import this from a persistent service: wires outbox relay, inbox guard and Kafka error handling. */
@Configuration
@EnableScheduling
@ComponentScan({"com.example.shop.common.outbox", "com.example.shop.common.inbox"})
@Import(KafkaCommonConfig.class)
public class CommonConfig {
}
