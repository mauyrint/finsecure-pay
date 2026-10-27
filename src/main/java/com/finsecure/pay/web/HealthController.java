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
 * Health endpoint used in three places: the public smoke test, {@code curl} inside the
 * container (Docker HEALTHCHECK), and the Kubernetes startup/readiness/liveness probes.
 * Returns 200 "UP" when the app accepts traffic and 503 "OUT_OF_SERVICE" otherwise
 * (e.g. during graceful shutdown), so Kubernetes stops routing to a pod that is closing.
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
