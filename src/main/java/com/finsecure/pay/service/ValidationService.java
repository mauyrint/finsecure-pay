package com.finsecure.pay.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationError;
import com.finsecure.pay.model.ValidationResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Orchestrates validation and emits the business metrics and audit logs used for
 * dashboards, alerting and troubleshooting.
 *
 * <p>Prometheus series produced:
 * <ul>
 *   <li>{@code finsecure_transactions_validated_total{outcome="approved|rejected"}}</li>
 *   <li>{@code finsecure_validation_failures_total{rule="AMOUNT_LIMIT_EXCEEDED",...}}</li>
 *   <li>{@code finsecure_transactions_review_required_total}</li>
 *   <li>{@code finsecure_transaction_amount_*{currency=...}} (approved amounts)</li>
 *   <li>{@code finsecure_validation_duration_seconds_*}</li>
 * </ul>
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final TransactionValidator validator;
    private final MeterRegistry registry;
    private final Counter approved;
    private final Counter rejected;
    private final Counter reviewRequired;
    private final Timer duration;

    public ValidationService(TransactionValidator validator, MeterRegistry registry) {
        this.validator = validator;
        this.registry = registry;
        this.approved = Counter.builder("finsecure.transactions.validated")
                .description("Transactions validated, by outcome")
                .tag("outcome", "approved").register(registry);
        this.rejected = Counter.builder("finsecure.transactions.validated")
                .description("Transactions validated, by outcome")
                .tag("outcome", "rejected").register(registry);
        this.reviewRequired = Counter.builder("finsecure.transactions.review.required")
                .description("Approved transactions flagged for manual compliance review")
                .register(registry);
        this.duration = Timer.builder("finsecure.validation.duration")
                .description("Time spent evaluating validation rules")
                .publishPercentileHistogram()
                .register(registry);
    }

    public ValidationResult validate(TransactionRequest request) {
        ValidationResult result = duration.record(() -> validator.validate(request));
        if (result.valid()) {
            approved.increment();
            if (result.reviewRequired()) {
                reviewRequired.increment();
            }
            recordAmount(request.currency(), request.amount());
            log.info("transaction approved txnId={} source={} destination={} riskLevel={} reviewRequired={}",
                    result.transactionId(), mask(request.sourceAccount()), mask(request.destinationAccount()),
                    result.riskLevel(), result.reviewRequired());
        } else {
            rejected.increment();
            result.errors().forEach(e -> registry.counter("finsecure.validation.failures", "rule", e.code()).increment());
            log.warn("transaction rejected txnId={} source={} destination={} reasons={}",
                    result.transactionId(),
                    request == null ? null : mask(request.sourceAccount()),
                    request == null ? null : mask(request.destinationAccount()),
                    result.errors().stream().map(ValidationError::code).collect(Collectors.joining(",")));
        }
        return result;
    }

    private void recordAmount(String currency, BigDecimal amount) {
        DistributionSummary.builder("finsecure.transaction.amount")
                .description("Approved transaction amounts")
                .tag("currency", currency.trim().toUpperCase(Locale.ROOT))
                .register(registry)
                .record(amount.doubleValue());
    }

    /** Never log full account numbers (PCI/PII hygiene): ACC12345678 -> ACC****5678. */
    static String mask(String account) {
        if (account == null || account.length() < 4) {
            return "****";
        }
        String trimmed = account.trim();
        if (trimmed.length() < 4) {
            return "****";
        }
        return trimmed.substring(0, Math.min(3, trimmed.length())) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
