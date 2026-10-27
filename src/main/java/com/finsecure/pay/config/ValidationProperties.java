package com.finsecure.pay.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finsecure.validation")
public record ValidationProperties(
        BigDecimal maxAmount,
        BigDecimal reviewThreshold,
        Set<String> supportedCurrencies,
        Set<String> blockedAccounts,
        String accountPattern,
        Duration maxTransactionAge,
        Duration allowedClockSkew) {

    public static ValidationProperties defaults() {
        return new ValidationProperties(
                new BigDecimal("10000.00"),
                new BigDecimal("5000.00"),
                Set.of("USD", "EUR", "GBP", "SGD", "THB"),
                Set.of(),
                "^ACC[0-9]{8}$",
                Duration.ofHours(24),
                Duration.ofMinutes(5));
    }
}
