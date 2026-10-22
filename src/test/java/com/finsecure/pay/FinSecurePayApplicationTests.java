package com.finsecure.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.finsecure.pay.config.ValidationProperties;

import io.micrometer.core.instrument.MeterRegistry;

/** Boots the full application context (externalised config, filters, metrics) end-to-end. */
@SpringBootTest(properties = {
        "finsecure.validation.max-amount=2000",
        "finsecure.validation.review-threshold=1500",
        "finsecure.validation.blocked-accounts=ACC00000013",
        "finsecure.security.api-key=test-key",
        "finsecure.app-version=it-test",
        "management.server.port=-1"
})
class FinSecurePayApplicationTests {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ValidationProperties rules;
    @Autowired
    private MeterRegistry registry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(com.finsecure.pay.web.RequestIdFilter.class),
                        context.getBean(com.finsecure.pay.web.ApiKeyFilter.class))
                .build();
    }

    @Test
    void bindsExternalisedConfiguration() {
        assertThat(rules.maxAmount()).isEqualByComparingTo("2000");
        assertThat(rules.blockedAccounts()).containsExactly("ACC00000013");
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("it-test"));
    }

    @Test
    void validateRequiresApiKey() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredLimitIsEnforcedEndToEnd() throws Exception {
        String body = """
                {"transactionId":"TXN-3001","sourceAccount":"ACC12345678",
                 "destinationAccount":"ACC87654321","amount":2500.00,"currency":"USD"}
                """;
        mvc.perform(post("/validate").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_LIMIT_EXCEEDED"));

        assertThat(registry.get("finsecure.validation.failures").tag("rule", "AMOUNT_LIMIT_EXCEEDED")
                .counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void approvesValidTransactionEndToEnd() throws Exception {
        String body = """
                {"transactionId":"TXN-3002","sourceAccount":"ACC12345678",
                 "destinationAccount":"ACC87654321","amount":1600.00,"currency":"SGD",
                 "timestamp":"%s"}
                """.formatted(java.time.Instant.now().minusSeconds(5));
        mvc.perform(post("/validate").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.reviewRequired").value(true));
    }
}
