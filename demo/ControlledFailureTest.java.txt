// DEMO (Task 4 / test case "Introduce a controlled failing test")
//   cp demo/ControlledFailureTest.java.txt src/test/java/com/finsecure/pay/ControlledFailureTest.java
//   git add -A && git commit -m "demo: controlled failing test" && git push
// Expected: Jenkins "Unit Test" stage FAILS, Package/Security/Push/Deploy are SKIPPED, failure e-mail sent.
// Revert:   git rm src/test/java/com/finsecure/pay/ControlledFailureTest.java && git commit -m "demo: remove failing test" && git push
package com.finsecure.pay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;

import org.junit.jupiter.api.Test;

import com.finsecure.pay.config.ValidationProperties;
import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.service.TransactionValidator;

class ControlledFailureTest {

    @Test
    void amountAboveLimitMustBeApproved_intentionallyWrong() {
        TransactionValidator validator = new TransactionValidator(ValidationProperties.defaults(), Clock.systemUTC());
        TransactionRequest overLimit = new TransactionRequest("DEMO-FAIL-1", "ACC12345678", "ACC87654321",
                new BigDecimal("999999.00"), "USD", null);
        // Deliberately incorrect expectation -> this test fails and must block the deployment
        assertThat(validator.validate(overLimit).valid()).isTrue();
    }
}
