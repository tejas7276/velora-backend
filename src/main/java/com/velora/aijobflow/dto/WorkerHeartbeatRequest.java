package com.velora.aijobflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerHeartbeatRequest {

    @NotBlank(message = "Worker name is required")
    private String workerName;

    private int activeJobs;
    private double cpuUsage;
    private double ramUsage;
}
