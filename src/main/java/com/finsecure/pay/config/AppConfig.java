package com.finsecure.pay.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
