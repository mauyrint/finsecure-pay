package com.finsecure.pay.model;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of validating a {@link TransactionRequest}.
 * {@code riskLevel} is only assessed for approved transactions; {@code reviewRequired}
 * flags approved high-value payments for manual compliance review.
 */
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
