package com.finsecure.pay.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    private MockMvc mvcWith(ReadinessState state) {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(state);
        return MockMvcBuilders.standaloneSetup(
                new HealthController(availability, Clock.systemUTC(), "1.0.0-test")).build();
    }

    @Test
    void reportsUpWhenAcceptingTraffic() throws Exception {
        mvcWith(ReadinessState.ACCEPTING_TRAFFIC).perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("finsecure-pay"))
                .andExpect(jsonPath("$.version").value("1.0.0-test"));
    }

    @Test
    void reports503WhenRefusingTraffic() throws Exception {
        mvcWith(ReadinessState.REFUSING_TRAFFIC).perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }
}
