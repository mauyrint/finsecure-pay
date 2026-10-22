package com.finsecure.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API key protecting {@code POST /validate}. The value is injected at runtime from the
 * Kubernetes Secret {@code finsecure-pay-secrets} (env {@code FINSECURE_SECURITY_API_KEY}).
 * When blank (local development) the check is disabled.
 */
@ConfigurationProperties(prefix = "finsecure.security")
public record SecurityProperties(String apiKey) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
