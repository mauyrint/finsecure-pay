package com.finsecure.pay.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        Instant timestamp) {
}
