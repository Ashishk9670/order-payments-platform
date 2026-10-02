package com.example.shop.inventory;

import com.example.shop.common.CommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Import(CommonConfig.class)
@EntityScan({"com.example.shop.inventory", "com.example.shop.common"})
@EnableJpaRepositories({"com.example.shop.inventory", "com.example.shop.common"})
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
