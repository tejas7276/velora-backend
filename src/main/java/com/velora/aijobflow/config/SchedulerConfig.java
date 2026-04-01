package com.velora.aijobflow.config;
import com.velora.aijobflow.service.JobService;
import com.velora.aijobflow.service.WorkerService;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulerConfig {

    private final WorkerService workerService;
    private final JobService jobService;

    // Every 2 minutes — mark workers with no heartbeat as inactive
    @Scheduled(fixedDelay = 120_000)
    public void checkStaleWorkers() {
        workerService.markStaleWorkersInactive();
    }

    // Every 60 seconds — release scheduled jobs whose time has arrived
    @Scheduled(fixedDelay = 60_000)
    public void checkScheduledJobs() {

        jobService.getAllJobs().stream()
                .filter(job -> "SCHEDULED".equals(job.getStatus()))
                .filter(job -> job.getScheduledAt() != null)
                .filter(job -> job.getScheduledAt().isBefore(LocalDateTime.now()))
                .forEach(jobService::releaseScheduledJob);
    }
}