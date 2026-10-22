package com.finsecure.pay.web;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.finsecure.pay.config.ValidationProperties;
import com.finsecure.pay.service.TransactionValidator;
import com.finsecure.pay.service.ValidationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Web-layer tests using a standalone MockMvc (fast, no Spring context). */
class TransactionControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ValidationService service = new ValidationService(
                new TransactionValidator(ValidationProperties.defaults(), Clock.systemUTC()), new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(new TransactionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void approvedTransactionReturns200() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"transactionId":"TXN-2001","sourceAccount":"ACC12345678",
                         "destinationAccount":"ACC87654321","amount":125.50,"currency":"THB"}
                        """))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void rejectedTransactionReturns422WithAllReasons() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"transactionId":"TXN-2002","sourceAccount":"ACC12345678",
                         "destinationAccount":"ACC12345678","amount":-5,"currency":"XYZ"}
                        """))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[*].code").value(hasItems(
                        "SAME_ACCOUNT", "AMOUNT_NOT_POSITIVE", "CURRENCY_UNSUPPORTED")));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongContentTypeReturns415() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mvc.perform(get("/validate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void propagatesValidIncomingRequestId() throws Exception {
        mvc.perform(post("/validate").header(RequestIdFilter.HEADER, "abc-12345-def")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(422))
                .andExpect(header().string(RequestIdFilter.HEADER, "abc-12345-def"));
    }
}
