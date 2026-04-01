package com.velora.aijobflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricsResponse {
    private long totalJobs;
    private long pendingJobs;
    private long processingJobs;
    private long completedJobs;
    private long failedJobs;
    private int  activeWorkers;
    private long queueSize;
    // Added: scheduled jobs count for frontend SCHEDULED banner
    private long scheduledJobs;
}