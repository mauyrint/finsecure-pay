package com.finsecure.pay.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Incoming payment instruction to be validated.
 *
 * @param transactionId        client supplied idempotency key
 * @param sourceAccount        debtor account, format {@code ACC########}
 * @param destinationAccount   creditor account, format {@code ACC########}
 * @param amount               positive amount with at most two decimal places
 * @param currency             ISO-4217 code, must be in the supported list
 * @param timestamp            optional time the transaction was initiated
 */
public record TransactionRequest(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        Instant timestamp) {
}
