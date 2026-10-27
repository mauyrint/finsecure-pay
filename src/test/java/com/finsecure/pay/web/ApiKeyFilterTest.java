package com.finsecure.pay.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private MockHttpServletResponse run(String configuredKey, String uri, String providedKey) throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(configuredKey);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (providedKey != null) {
            request.addHeader(ApiKeyFilter.HEADER, providedKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void disabledWhenNoKeyConfigured() throws Exception {
        assertThat(run("", "/validate", null).getStatus()).isEqualTo(200);
        assertThat(run(null, "/validate", null).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingKey() throws Exception {
        MockHttpServletResponse response = run("s3cr3t", "/validate", null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void rejectsWrongKey() throws Exception {
        assertThat(run("s3cr3t", "/validate", "guess").getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsCorrectKey() throws Exception {
        assertThat(run("s3cr3t", "/validate", "s3cr3t").getStatus()).isEqualTo(200);
    }

    @Test
    void healthEndpointIsAlwaysPublic() throws Exception {
        assertThat(run("s3cr3t", "/health", null).getStatus()).isEqualTo(200);
    }
}
