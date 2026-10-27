package com.finsecure.pay.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationError;
import com.finsecure.pay.model.ValidationResult;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final TransactionValidator validator;
    private final MeterRegistry registry;

    public ValidationService(TransactionValidator validator, MeterRegistry registry) {
        this.validator = validator;
        this.registry = registry;
        registry.counter("finsecure.transactions.validated", "outcome", "approved");
        registry.counter("finsecure.transactions.validated", "outcome", "rejected");
    }

    public ValidationResult validate(TransactionRequest request) {
        ValidationResult result = validator.validate(request);

        if (result.valid()) {
            registry.counter("finsecure.transactions.validated", "outcome", "approved").increment();
            String currency = request.currency().trim().toUpperCase(Locale.ROOT);
            registry.summary("finsecure.transaction.amount", "currency", currency).record(request.amount().doubleValue());

            log.info("transaction approved txnId={} source={} destination={} riskLevel={} reviewRequired={}",
                    result.transactionId(), mask(request.sourceAccount()), mask(request.destinationAccount()),
                    result.riskLevel(), result.reviewRequired());
        } else {
            registry.counter("finsecure.transactions.validated", "outcome", "rejected").increment();
            List<String> reasons = new ArrayList<>();
            for (ValidationError error : result.errors()) {
                registry.counter("finsecure.validation.failures", "rule", error.code()).increment();
                reasons.add(error.code());
            }

            String source = request == null ? null : mask(request.sourceAccount());
            String destination = request == null ? null : mask(request.destinationAccount());
            log.warn("transaction rejected txnId={} source={} destination={} reasons={}",
                    result.transactionId(), source, destination, String.join(",", reasons));
        }
        return result;
    }

    static String mask(String account) {
        if (account == null || account.trim().length() < 4) {
            return "****";
        }
        String value = account.trim();
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
