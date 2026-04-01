package com.velora.aijobflow.service;

import com.velora.aijobflow.dto.MetricsResponse;
import com.velora.aijobflow.repository.JobRepository;
import com.velora.aijobflow.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetricsService {

    private final JobRepository    jobRepository;
    private final WorkerRepository workerRepository;

    @Transactional(readOnly = true)
    public MetricsResponse getMetrics() {
        long totalJobs      = jobRepository.count();
        long pendingJobs    = jobRepository.countByStatus("PENDING");
        long processingJobs = jobRepository.countByStatus("PROCESSING");
        long completedJobs  = jobRepository.countByStatus("COMPLETED");
        long failedJobs     = jobRepository.countByStatus("FAILED");
        long scheduledJobs  = jobRepository.countByStatus("SCHEDULED");
        long activeWorkers  = workerRepository.countByStatus("ACTIVE");

        return new MetricsResponse(
                totalJobs,
                pendingJobs,
                processingJobs,
                completedJobs,
                failedJobs,
                (int) activeWorkers,
                pendingJobs,   // queue size ≈ pending jobs waiting
                scheduledJobs  // scheduled jobs waiting for their time
        );
    }
}