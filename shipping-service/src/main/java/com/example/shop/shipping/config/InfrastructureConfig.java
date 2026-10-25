package com.example.shop.shipping.config;

import com.example.shop.common.CommonConfig;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Kept out of the main class so web-layer slice tests do not try to boot JPA, the outbox and Kafka. */
@Configuration
@Import(CommonConfig.class)
@EntityScan({"com.example.shop.shipping", "com.example.shop.common"})
@EnableJpaRepositories({"com.example.shop.shipping", "com.example.shop.common"})
class InfrastructureConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
