package com.finsecure.pay.model;

import java.time.Instant;
import java.util.List;

public record ValidationResult(
        String transactionId,
        ValidationStatus status,
        boolean valid,
        RiskLevel riskLevel,
        boolean reviewRequired,
        List<ValidationError> errors,
        Instant validatedAt) {

    public static ValidationResult approved(String transactionId, RiskLevel risk, boolean review, Instant at) {
        return new ValidationResult(transactionId, ValidationStatus.APPROVED, true, risk, review, List.of(), at);
    }

    public static ValidationResult rejected(String transactionId, List<ValidationError> errors, Instant at) {
        return new ValidationResult(transactionId, ValidationStatus.REJECTED, false, null, false, List.copyOf(errors), at);
    }
}
