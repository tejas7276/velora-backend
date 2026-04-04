package com.velora.aijobflow.service;

import com.velora.aijobflow.model.Job;
import com.velora.aijobflow.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository  jobRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${queue.job.exchange}")
    private String exchange;

    @Value("${queue.job.routing-key}")
    private String routingKey;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Transactional
    public Job createJob(Long userId, String jobType, String payload,
                         String filePath, String documentText,
                         LocalDateTime scheduledAt, String aiModel, String priority) {
        Job job = new Job();
        job.setUserId(userId);
        job.setJobType(jobType);
        job.setPayload(payload);
        job.setFilePath(filePath);
        job.setDocumentText(documentText);
        job.setAiModel(aiModel);
        job.setPriority(priority != null ? priority : "MEDIUM");
        job.setStatus(scheduledAt != null ? "SCHEDULED" : "PENDING");
        job.setScheduledAt(scheduledAt);
        job.setRetryCount(0);

        Job saved = jobRepository.save(job);
        log.info("Job created: id={} type={} model={} priority={} scheduled={} hasDoc={}",
                saved.getId(), jobType, aiModel, priority, scheduledAt,
                documentText != null && !documentText.isBlank());

        if (scheduledAt == null) {
            dispatchToQueue(saved);
        }
        return saved;
    }

    /** Backward-compatible overload — no document, no scheduling. */
    @Transactional
    public Job createJob(Long userId, String jobType, String payload, String filePath) {
        return createJob(userId, jobType, payload, filePath, null, null, null, "MEDIUM");
    }

    // ── COMPLETE / FAIL ───────────────────────────────────────────────────────

    @Transactional
    public void completeJob(Long jobId, String result, long processingTimeMs) {
        Job job = findOrThrow(jobId);
        job.setStatus("COMPLETED");
        job.setResult(result);
        job.setProcessingTime(processingTimeMs);
        jobRepository.save(job);
        log.info("Job {} COMPLETED in {}ms", jobId, processingTimeMs);
    }

    @Transactional
    public void completeJob(Long jobId, String result) {
        completeJob(jobId, result, 0L);
    }

    @Transactional
    public void failJob(Long jobId, String errorMessage) {
        Job job = findOrThrow(jobId);
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        jobRepository.save(job);
        log.warn("Job {} FAILED: {}", jobId, errorMessage);
    }

    // ── RETRY ─────────────────────────────────────────────────────────────────

    @Transactional
    public Job retryJob(Long jobId) {
        return retryJob(jobId, null);
    }

    @Transactional
    public Job retryJob(Long jobId, Long userId) {
        Job job = findOrThrow(jobId);
        if (userId != null && !job.getUserId().equals(userId)) {
            throw new SecurityException("Access denied to job " + jobId);
        }
        if (!"FAILED".equals(job.getStatus()) && !"CANCELLED".equals(job.getStatus())) {
            throw new IllegalStateException("Only FAILED or CANCELLED jobs can be retried");
        }
        int retries = job.getRetryCount() != null ? job.getRetryCount() : 0;
        if (retries >= 3) {
            throw new IllegalStateException("Maximum retry limit (3) reached");
        }
        job.setStatus("PENDING");
        job.setRetryCount(retries + 1);
        job.setErrorMessage(null);
        jobRepository.save(job);
        dispatchToQueue(job);
        log.info("Job {} re-queued (retry #{})", jobId, job.getRetryCount());
        return job;
    }

    // ── CANCEL ────────────────────────────────────────────────────────────────

    @Transactional
    public Job cancelJob(Long jobId) {
        return cancelJob(jobId, null);
    }

    @Transactional
    public Job cancelJob(Long jobId, Long userId) {
        Job job = findOrThrow(jobId);
        if (userId != null && !job.getUserId().equals(userId)) {
            throw new SecurityException("Access denied to job " + jobId);
        }
        if ("COMPLETED".equals(job.getStatus()) || "FAILED".equals(job.getStatus())) {
            throw new IllegalStateException("Cannot cancel a " + job.getStatus() + " job");
        }
        job.setStatus("CANCELLED");
        jobRepository.save(job);
        log.info("Job {} CANCELLED", jobId);
        return job;
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Job> getUserJobs(Long userId) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Job> getUserJobsByStatus(Long userId, String status) {
        return jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
    }

    @Transactional(readOnly = true)
    public List<Job> getJobsByStatusForUser(Long userId, String status) {
        if (status != null && !status.isBlank()) {
            return jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }
        return jobRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Job> getAllJobs() {
        return jobRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Job getJobById(Long jobId) {
        return findOrThrow(jobId);
    }

    @Transactional(readOnly = true)
    public Job getJobByIdForUser(Long jobId, Long userId) {
        Job job = findOrThrow(jobId);
        if (!job.getUserId().equals(userId)) {
            throw new SecurityException("Access denied to job " + jobId);
        }
        return job;
    }

    @Transactional
    public void updateJobStatus(Long jobId, String status) {
        Job job = findOrThrow(jobId);
        job.setStatus(status);
        jobRepository.save(job);
    }

    // ── SCHEDULED JOB RELEASE ─────────────────────────────────────────────────

    @Transactional
    public void releaseScheduledJob(Job job) {
        job.setStatus("PENDING");
        jobRepository.save(job);
        dispatchToQueue(job);
        log.info("Scheduled job {} released to queue", job.getId());
    }

    // ── QUEUE DISPATCH ────────────────────────────────────────────────────────

    /**
     * ROOT CAUSE FIX — Issue 2:
     *
     * rabbitTemplate.convertAndSend() throws AmqpIOException /
     * ShutdownSignalException / EOFException when RabbitMQ is unavailable.
     *
     * Previously this was called directly inside @Transactional createJob(),
     * which meant a RabbitMQ failure caused the entire API call to return 500.
     * The job WAS saved to DB (save() happened first) but the API reported failure.
     *
     * Fix: Isolate all RabbitMQ calls in this method with try-catch.
     * - Job is ALWAYS saved to DB first (that never fails due to RabbitMQ).
     * - If RabbitMQ is down: log a warning, leave job in PENDING status.
     * - The scheduler (SchedulerConfig) can pick up PENDING jobs and retry.
     * - The API always returns 201 Created with the saved job.
     * - No 500 errors from RabbitMQ failures.
     *
     * AmqpException is the base class for all Spring AMQP exceptions including:
     *   AmqpIOException, AmqpConnectException, AmqpTimeoutException.
     * We also catch RuntimeException to cover ShutdownSignalException which
     * is a com.rabbitmq.client exception (not AmqpException subclass).
     */
    private void dispatchToQueue(Job job) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, job);
            log.info("Job {} dispatched to queue", job.getId());
        } catch (RuntimeException ex) {
            // RabbitMQ is down or connection refused.
            // Job stays in DB with PENDING status — scheduler will retry.
            // API call succeeds — client is NOT notified of queue failure.
            log.warn("Job {} could not be queued (RabbitMQ unavailable): {}. " +
                     "Job saved as PENDING — will retry via scheduler.",
                     job.getId(), ex.getMessage());
        }
    }

    // ── INTERNAL ──────────────────────────────────────────────────────────────

    private Job findOrThrow(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
    }
}