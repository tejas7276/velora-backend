package com.velora.aijobflow.controller;

import com.velora.aijobflow.dto.MetricsResponse;
import com.velora.aijobflow.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping
    public ResponseEntity<MetricsResponse> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetrics());
    }
}
