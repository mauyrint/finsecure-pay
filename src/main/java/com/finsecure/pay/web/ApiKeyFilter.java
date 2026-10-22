package com.finsecure.pay.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.finsecure.pay.config.SecurityProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Requires a valid {@code X-API-Key} header on {@code /validate} when an API key is configured.
 * The key lives only in a Kubernetes Secret; comparison is constant-time.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final SecurityProperties security;

    public ApiKeyFilter(SecurityProperties security) {
        this.security = security;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !security.enabled() || !request.getRequestURI().startsWith("/validate");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), security.apiKey().getBytes(StandardCharsets.UTF_8))) {
            log.warn("rejected request with missing or invalid API key from {}", request.getRemoteAddr());
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-API-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
