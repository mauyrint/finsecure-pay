package com.finsecure.pay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.finsecure.pay.config.ValidationProperties;
import com.finsecure.pay.model.RiskLevel;
import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationError;
import com.finsecure.pay.model.ValidationResult;
import com.finsecure.pay.model.ValidationStatus;

@DisplayName("TransactionValidator business rules")
class TransactionValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TransactionValidator validator;

    @BeforeEach
    void setUp() {
        ValidationProperties rules = new ValidationProperties(
                new BigDecimal("10000.00"), new BigDecimal("5000.00"),
                Set.of("USD", "EUR", "THB"), Set.of("ACC99999999"),
                "^ACC[0-9]{8}$", Duration.ofHours(24), Duration.ofMinutes(5));
        validator = new TransactionValidator(rules, CLOCK);
    }

    private static TransactionRequest valid() {
        return new TransactionRequest("TXN-0001", "ACC12345678", "ACC87654321",
                new BigDecimal("250.00"), "USD", NOW.minusSeconds(30));
    }

    private static TransactionRequest withAmount(String amount) {
        TransactionRequest v = valid();
        return new TransactionRequest(v.transactionId(), v.sourceAccount(), v.destinationAccount(),
                amount == null ? null : new BigDecimal(amount), v.currency(), v.timestamp());
    }

    private static Set<String> codes(ValidationResult result) {
        return result.errors().stream().map(ValidationError::code).collect(java.util.stream.Collectors.toSet());
    }

    @Nested
    @DisplayName("successful validation")
    class Successful {

        @Test
        void approvesAWellFormedTransaction() {
            ValidationResult result = validator.validate(valid());

            assertThat(result.valid()).isTrue();
            assertThat(result.status()).isEqualTo(ValidationStatus.APPROVED);
            assertThat(result.errors()).isEmpty();
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
            assertThat(result.reviewRequired()).isFalse();
            assertThat(result.transactionId()).isEqualTo("TXN-0001");
            assertThat(result.validatedAt()).isEqualTo(NOW);
        }

        @Test
        void timestampIsOptional() {
            TransactionRequest v = valid();
            TransactionRequest noTs = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), v.currency(), null);
            assertThat(validator.validate(noTs).valid()).isTrue();
        }

        @Test
        void acceptsLowerCaseCurrencyAndAccounts() {
            TransactionRequest req = new TransactionRequest("TXN-0002", "acc12345678", "acc87654321",
                    new BigDecimal("10"), "eur", null);
            assertThat(validator.validate(req).valid()).isTrue();
        }

        @Test
        void amountExactlyAtLimitIsAllowedAndFlaggedForReview() {
            ValidationResult result = validator.validate(withAmount("10000.00"));
            assertThat(result.valid()).isTrue();
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(result.reviewRequired()).isTrue();
        }

        @ParameterizedTest(name = "amount {0} -> {1}")
        @CsvSource({"0.01, LOW", "999.99, LOW", "1000.00, MEDIUM", "4999.99, MEDIUM", "5000.00, HIGH"})
        void assessesRiskByAmount(String amount, RiskLevel expected) {
            assertThat(validator.validate(withAmount(amount)).riskLevel()).isEqualTo(expected);
        }

        @Test
        void trailingZerosDoNotCountAsExtraPrecision() {
            assertThat(validator.validate(withAmount("12.5000")).valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("failed validation")
    class Failed {

        @Test
        void rejectsNullRequest() {
            ValidationResult result = validator.validate(null);
            assertThat(result.valid()).isFalse();
            assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void requiresTransactionId(String id) {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(id, v.sourceAccount(), v.destinationAccount(),
                    v.amount(), v.currency(), v.timestamp());
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.TXN_ID_REQUIRED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ABC", "TXN 0001", "TXN_0001", "DROP TABLE;"})
        void rejectsMalformedTransactionId(String id) {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(id, v.sourceAccount(), v.destinationAccount(),
                    v.amount(), v.currency(), v.timestamp());
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.TXN_ID_INVALID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345678", "ACC1234", "ACC123456789", "XYZ12345678"})
        void rejectsMalformedSourceAccount(String account) {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), account, v.destinationAccount(),
                    v.amount(), v.currency(), v.timestamp());
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.SOURCE_ACCOUNT_INVALID);
        }

        @Test
        void requiresBothAccounts() {
            TransactionRequest req = new TransactionRequest("TXN-0003", null, " ",
                    BigDecimal.TEN, "USD", null);
            assertThat(codes(validator.validate(req))).containsExactlyInAnyOrder(
                    TransactionValidator.SOURCE_ACCOUNT_REQUIRED, TransactionValidator.DESTINATION_ACCOUNT_REQUIRED);
        }

        @Test
        void rejectsMalformedDestinationAccount() {
            TransactionRequest req = new TransactionRequest("TXN-0003", "ACC12345678", "BAD",
                    BigDecimal.TEN, "USD", null);
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.DESTINATION_ACCOUNT_INVALID);
        }

        @Test
        void rejectsTransferToSameAccount() {
            TransactionRequest req = new TransactionRequest("TXN-0004", "ACC12345678", "acc12345678",
                    BigDecimal.TEN, "USD", null);
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.SAME_ACCOUNT);
        }

        @Test
        void rejectsBlockedAccount() {
            TransactionRequest req = new TransactionRequest("TXN-0005", "ACC12345678", "ACC99999999",
                    BigDecimal.TEN, "USD", null);
            ValidationResult result = validator.validate(req);
            assertThat(codes(result)).containsExactly(TransactionValidator.ACCOUNT_BLOCKED);
            assertThat(result.errors().get(0).field()).isEqualTo("destinationAccount");
        }

        @Test
        void requiresAmount() {
            assertThat(codes(validator.validate(withAmount(null)))).containsExactly(TransactionValidator.AMOUNT_REQUIRED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
        void rejectsNonPositiveAmount(String amount) {
            assertThat(codes(validator.validate(withAmount(amount))))
                    .containsExactly(TransactionValidator.AMOUNT_NOT_POSITIVE);
        }

        @Test
        void rejectsMoreThanTwoDecimalPlaces() {
            assertThat(codes(validator.validate(withAmount("10.001"))))
                    .containsExactly(TransactionValidator.AMOUNT_PRECISION);
        }

        @Test
        void rejectsAmountAboveLimit() {
            assertThat(codes(validator.validate(withAmount("10000.01"))))
                    .containsExactly(TransactionValidator.AMOUNT_LIMIT_EXCEEDED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void requiresCurrency(String currency) {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), currency, null);
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.CURRENCY_REQUIRED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"JPY", "BTC", "US"})
        void rejectsUnsupportedCurrency(String currency) {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), currency, null);
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.CURRENCY_UNSUPPORTED);
        }

        @Test
        void rejectsTimestampInTheFuture() {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), v.currency(), NOW.plus(Duration.ofMinutes(10)));
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.TIMESTAMP_IN_FUTURE);
        }

        @Test
        void allowsSmallClockSkew() {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), v.currency(), NOW.plus(Duration.ofMinutes(4)));
            assertThat(validator.validate(req).valid()).isTrue();
        }

        @Test
        void rejectsExpiredTimestamp() {
            TransactionRequest v = valid();
            TransactionRequest req = new TransactionRequest(v.transactionId(), v.sourceAccount(),
                    v.destinationAccount(), v.amount(), v.currency(), NOW.minus(Duration.ofHours(25)));
            assertThat(codes(validator.validate(req))).containsExactly(TransactionValidator.TIMESTAMP_EXPIRED);
        }

        @Test
        void reportsAllFailuresAtOnce() {
            TransactionRequest req = new TransactionRequest("", "bad", "ACC12345678",
                    new BigDecimal("50000.123"), "JPY", null);
            ValidationResult result = validator.validate(req);

            assertThat(result.valid()).isFalse();
            assertThat(result.riskLevel()).isNull();
            assertThat(codes(result)).containsExactlyInAnyOrder(
                    TransactionValidator.TXN_ID_REQUIRED,
                    TransactionValidator.SOURCE_ACCOUNT_INVALID,
                    TransactionValidator.AMOUNT_PRECISION,
                    TransactionValidator.AMOUNT_LIMIT_EXCEEDED,
                    TransactionValidator.CURRENCY_UNSUPPORTED);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void defaultsAreSensible() {
            ValidationProperties d = ValidationProperties.defaults();
            assertThat(d.maxAmount()).isEqualByComparingTo("10000");
            assertThat(d.reviewThreshold()).isEqualByComparingTo("5000");
            assertThat(d.supportedCurrencies()).contains("USD", "THB");
            assertThat(d.blockedAccounts()).isEmpty();
        }

        @Test
        void usesConfiguredLimit() {
            ValidationProperties small = new ValidationProperties(new BigDecimal("100.00"), new BigDecimal("50.00"),
                    Set.of("USD"), Set.of(), "^ACC[0-9]{8}$", Duration.ofHours(24), Duration.ofMinutes(5));
            ValidationResult result = new TransactionValidator(small, CLOCK).validate(valid());
            assertThat(codes(result)).containsExactly(TransactionValidator.AMOUNT_LIMIT_EXCEEDED);
        }

        @Test
        void usesConfiguredBlockList() {
            ValidationProperties rules = new ValidationProperties(new BigDecimal("10000.00"), new BigDecimal("5000.00"),
                    Set.of("USD"), Set.of("ACC87654321"), "^ACC[0-9]{8}$", Duration.ofHours(24), Duration.ofMinutes(5));
            ValidationResult result = new TransactionValidator(rules, CLOCK).validate(valid());
            assertThat(codes(result)).containsExactly(TransactionValidator.ACCOUNT_BLOCKED);
        }
    }
}
