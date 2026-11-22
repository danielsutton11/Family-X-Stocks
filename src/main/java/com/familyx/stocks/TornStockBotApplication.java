package com.familyx.stocks;

import com.familyx.stocks.config.BotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BotProperties.class)
public class TornStockBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(TornStockBotApplication.class, args);
    }
}
