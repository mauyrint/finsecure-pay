package com.finsecure.pay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.finsecure.pay.config.ValidationProperties;
import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ValidationServiceTest {

    private SimpleMeterRegistry registry;
    private ValidationService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ValidationService(
                new TransactionValidator(ValidationProperties.defaults(), Clock.systemUTC()), registry);
    }

    private double count(String name, String tagKey, String tagValue) {
        var counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void recordsApprovedTransactionMetrics() {
        ValidationResult result = service.validate(new TransactionRequest("TXN-1001", "ACC12345678",
                "ACC87654321", new BigDecimal("7500.00"), "usd", null));

        assertThat(result.valid()).isTrue();
        assertThat(count("finsecure.transactions.validated", "outcome", "approved")).isEqualTo(1);
        assertThat(count("finsecure.transactions.validated", "outcome", "rejected")).isZero();
        assertThat(registry.get("finsecure.transactions.review.required").counter().count()).isEqualTo(1);
        assertThat(registry.get("finsecure.transaction.amount").tag("currency", "USD").summary().totalAmount())
                .isEqualTo(7500.0);
        assertThat(registry.get("finsecure.validation.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsRejectedTransactionMetricsPerRule() {
        ValidationResult result = service.validate(new TransactionRequest("TXN-1002", "ACC12345678",
                "ACC12345678", new BigDecimal("20000"), "JPY", null));

        assertThat(result.valid()).isFalse();
        assertThat(count("finsecure.transactions.validated", "outcome", "rejected")).isEqualTo(1);
        assertThat(count("finsecure.validation.failures", "rule", TransactionValidator.SAME_ACCOUNT)).isEqualTo(1);
        assertThat(count("finsecure.validation.failures", "rule", TransactionValidator.AMOUNT_LIMIT_EXCEEDED)).isEqualTo(1);
        assertThat(count("finsecure.validation.failures", "rule", TransactionValidator.CURRENCY_UNSUPPORTED)).isEqualTo(1);
    }

    @Test
    void handlesNullRequest() {
        assertThat(service.validate(null).valid()).isFalse();
        assertThat(count("finsecure.transactions.validated", "outcome", "rejected")).isEqualTo(1);
    }

    @Test
    void masksAccountNumbersForLogs() {
        assertThat(ValidationService.mask("ACC12345678")).isEqualTo("ACC****5678");
        assertThat(ValidationService.mask(null)).isEqualTo("****");
        assertThat(ValidationService.mask("AB")).isEqualTo("****");
        assertThat(ValidationService.mask(" AB ")).isEqualTo("****");
    }
}
