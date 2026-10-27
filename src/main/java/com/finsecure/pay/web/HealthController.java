package com.finsecure.pay.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final ApplicationAvailability availability;
    private final String version;

    public HealthController(ApplicationAvailability availability,
                            @Value("${finsecure.app-version:dev}") String version) {
        this.availability = availability;
        this.version = version;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "OUT_OF_SERVICE");
        body.put("service", "finsecure-pay");
        body.put("version", version);

        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }
}
