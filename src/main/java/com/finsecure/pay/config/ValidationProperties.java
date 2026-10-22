package com.finsecure.pay.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rules that operators can tune without rebuilding the image.
 * In Kubernetes these values come from the {@code finsecure-pay-config} ConfigMap
 * (e.g. {@code FINSECURE_VALIDATION_MAX_AMOUNT=10000}).
 */
@ConfigurationProperties(prefix = "finsecure.validation")
public record ValidationProperties(
        BigDecimal maxAmount,
        BigDecimal reviewThreshold,
        Set<String> supportedCurrencies,
        Set<String> blockedAccounts,
        String accountPattern,
        Duration maxTransactionAge,
        Duration allowedClockSkew) {

    public ValidationProperties {
        maxAmount = maxAmount != null ? maxAmount : new BigDecimal("10000.00");
        reviewThreshold = reviewThreshold != null ? reviewThreshold : new BigDecimal("5000.00");
        supportedCurrencies = normalise(supportedCurrencies, Set.of("USD", "EUR", "GBP", "SGD", "THB"));
        blockedAccounts = normalise(blockedAccounts, Set.of());
        accountPattern = (accountPattern == null || accountPattern.isBlank()) ? "^ACC[0-9]{8}$" : accountPattern;
        maxTransactionAge = maxTransactionAge != null ? maxTransactionAge : Duration.ofHours(24);
        allowedClockSkew = allowedClockSkew != null ? allowedClockSkew : Duration.ofMinutes(5);
        if (reviewThreshold.compareTo(maxAmount) > 0) {
            throw new IllegalArgumentException("finsecure.validation.review-threshold must not exceed max-amount");
        }
    }

    /** Defaults used by unit tests and local runs. */
    public static ValidationProperties defaults() {
        return new ValidationProperties(null, null, null, null, null, null, null);
    }

    private static Set<String> normalise(Set<String> values, Set<String> fallback) {
        if (values == null) {
            return fallback;
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
