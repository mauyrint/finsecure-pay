package com.finsecure.pay.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, lightweight health endpoint used for smoke tests and external checks.
 * Kubernetes probes use the richer Actuator groups on the management port
 * ({@code /actuator/health/liveness} and {@code /actuator/health/readiness}).
 */
@RestController
public class HealthController {

    private final ApplicationAvailability availability;
    private final Clock clock;
    private final String version;
    private final Instant startedAt;

    public HealthController(ApplicationAvailability availability, Clock clock,
                            @Value("${finsecure.app-version:dev}") String version) {
        this.availability = availability;
        this.clock = clock;
        this.version = version;
        this.startedAt = clock.instant();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        Instant now = clock.instant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "OUT_OF_SERVICE");
        body.put("service", "finsecure-pay");
        body.put("version", version);
        body.put("uptimeSeconds", Duration.between(startedAt, now).toSeconds());
        body.put("timestamp", now.toString());
        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }
}
