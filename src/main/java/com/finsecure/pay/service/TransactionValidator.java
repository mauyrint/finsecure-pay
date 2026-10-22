package com.finsecure.pay.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.finsecure.pay.config.ValidationProperties;
import com.finsecure.pay.model.RiskLevel;
import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationError;
import com.finsecure.pay.model.ValidationResult;

/**
 * Pure business-rule engine for FinSecure Pay transactions. It has no framework
 * dependencies beyond the {@link Component} marker, so every rule is unit-testable in isolation.
 *
 * <p>All rules are evaluated (not fail-fast) so a client receives every problem in one response.
 */
@Component
public class TransactionValidator {

    public static final String TXN_ID_REQUIRED = "TXN_ID_REQUIRED";
    public static final String TXN_ID_INVALID = "TXN_ID_INVALID";
    public static final String SOURCE_ACCOUNT_REQUIRED = "SOURCE_ACCOUNT_REQUIRED";
    public static final String SOURCE_ACCOUNT_INVALID = "SOURCE_ACCOUNT_INVALID";
    public static final String DESTINATION_ACCOUNT_REQUIRED = "DESTINATION_ACCOUNT_REQUIRED";
    public static final String DESTINATION_ACCOUNT_INVALID = "DESTINATION_ACCOUNT_INVALID";
    public static final String SAME_ACCOUNT = "SAME_ACCOUNT";
    public static final String ACCOUNT_BLOCKED = "ACCOUNT_BLOCKED";
    public static final String AMOUNT_REQUIRED = "AMOUNT_REQUIRED";
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    public static final String AMOUNT_PRECISION = "AMOUNT_PRECISION";
    public static final String AMOUNT_LIMIT_EXCEEDED = "AMOUNT_LIMIT_EXCEEDED";
    public static final String CURRENCY_REQUIRED = "CURRENCY_REQUIRED";
    public static final String CURRENCY_UNSUPPORTED = "CURRENCY_UNSUPPORTED";
    public static final String TIMESTAMP_IN_FUTURE = "TIMESTAMP_IN_FUTURE";
    public static final String TIMESTAMP_EXPIRED = "TIMESTAMP_EXPIRED";

    private static final Pattern TXN_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{6,64}$");

    private final ValidationProperties rules;
    private final Pattern accountPattern;
    private final Clock clock;

    public TransactionValidator(ValidationProperties rules, Clock clock) {
        this.rules = rules;
        this.accountPattern = Pattern.compile(rules.accountPattern());
        this.clock = clock;
    }

    public ValidationResult validate(TransactionRequest request) {
        Instant now = clock.instant();
        if (request == null) {
            return ValidationResult.rejected(null,
                    List.of(new ValidationError(TXN_ID_REQUIRED, "transactionId", "Request body is required")), now);
        }

        List<ValidationError> errors = new ArrayList<>();
        validateTransactionId(request.transactionId(), errors);
        String source = validateAccount(request.sourceAccount(), "sourceAccount",
                SOURCE_ACCOUNT_REQUIRED, SOURCE_ACCOUNT_INVALID, errors);
        String destination = validateAccount(request.destinationAccount(), "destinationAccount",
                DESTINATION_ACCOUNT_REQUIRED, DESTINATION_ACCOUNT_INVALID, errors);
        validateAccountPair(source, destination, errors);
        validateAmount(request.amount(), errors);
        validateCurrency(request.currency(), errors);
        validateTimestamp(request.timestamp(), now, errors);

        if (!errors.isEmpty()) {
            return ValidationResult.rejected(request.transactionId(), errors, now);
        }
        RiskLevel risk = assessRisk(request.amount());
        return ValidationResult.approved(request.transactionId(), risk, risk == RiskLevel.HIGH, now);
    }

    private void validateTransactionId(String id, List<ValidationError> errors) {
        if (isBlank(id)) {
            errors.add(new ValidationError(TXN_ID_REQUIRED, "transactionId", "transactionId is required"));
        } else if (!TXN_ID_PATTERN.matcher(id).matches()) {
            errors.add(new ValidationError(TXN_ID_INVALID, "transactionId",
                    "transactionId must be 6-64 characters of letters, digits or '-'"));
        }
    }

    private String validateAccount(String account, String field, String requiredCode, String invalidCode,
                                   List<ValidationError> errors) {
        if (isBlank(account)) {
            errors.add(new ValidationError(requiredCode, field, field + " is required"));
            return null;
        }
        String normalised = account.trim().toUpperCase(Locale.ROOT);
        if (!accountPattern.matcher(normalised).matches()) {
            errors.add(new ValidationError(invalidCode, field, field + " must match " + rules.accountPattern()));
            return null;
        }
        if (rules.blockedAccounts().contains(normalised)) {
            errors.add(new ValidationError(ACCOUNT_BLOCKED, field, field + " is blocked for compliance reasons"));
        }
        return normalised;
    }

    private static void validateAccountPair(String source, String destination, List<ValidationError> errors) {
        if (source != null && source.equals(destination)) {
            errors.add(new ValidationError(SAME_ACCOUNT, "destinationAccount",
                    "sourceAccount and destinationAccount must be different"));
        }
    }

    private void validateAmount(BigDecimal amount, List<ValidationError> errors) {
        if (amount == null) {
            errors.add(new ValidationError(AMOUNT_REQUIRED, "amount", "amount is required"));
            return;
        }
        if (amount.signum() <= 0) {
            errors.add(new ValidationError(AMOUNT_NOT_POSITIVE, "amount", "amount must be greater than zero"));
            return;
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            errors.add(new ValidationError(AMOUNT_PRECISION, "amount", "amount must have at most 2 decimal places"));
        }
        if (amount.compareTo(rules.maxAmount()) > 0) {
            errors.add(new ValidationError(AMOUNT_LIMIT_EXCEEDED, "amount",
                    "amount exceeds the single-transaction limit of " + rules.maxAmount().toPlainString()));
        }
    }

    private void validateCurrency(String currency, List<ValidationError> errors) {
        if (isBlank(currency)) {
            errors.add(new ValidationError(CURRENCY_REQUIRED, "currency", "currency is required"));
        } else if (!rules.supportedCurrencies().contains(currency.trim().toUpperCase(Locale.ROOT))) {
            errors.add(new ValidationError(CURRENCY_UNSUPPORTED, "currency",
                    "currency must be one of " + rules.supportedCurrencies().stream().sorted().toList()));
        }
    }

    private void validateTimestamp(Instant timestamp, Instant now, List<ValidationError> errors) {
        if (timestamp == null) {
            return; // optional field
        }
        if (timestamp.isAfter(now.plus(rules.allowedClockSkew()))) {
            errors.add(new ValidationError(TIMESTAMP_IN_FUTURE, "timestamp", "timestamp is in the future"));
        } else if (timestamp.isBefore(now.minus(rules.maxTransactionAge()))) {
            errors.add(new ValidationError(TIMESTAMP_EXPIRED, "timestamp",
                    "timestamp is older than " + rules.maxTransactionAge()));
        }
    }

    RiskLevel assessRisk(BigDecimal amount) {
        if (amount.compareTo(rules.reviewThreshold()) >= 0) {
            return RiskLevel.HIGH;
        }
        BigDecimal medium = rules.reviewThreshold().divide(BigDecimal.valueOf(5));
        return amount.compareTo(medium) >= 0 ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
