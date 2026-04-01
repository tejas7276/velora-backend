package com.velora.aijobflow.controller;

import com.velora.aijobflow.model.Job;
import com.velora.aijobflow.security.SecurityUtils;
import com.velora.aijobflow.service.JobService;
import com.velora.aijobflow.util.PdfTextExtractor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  JobController — SECURITY-FIXED version
 * ═══════════════════════════════════════════════════════════════════
 *
 *  ELI5: What changed and WHY it matters:
 *
 *  BEFORE (broken):
 *    @RequestHeader("X-User-Id") Long userId
 *    → Browser sends the userId. Hacker can send userId=1 (admin).
 *
 *  AFTER (secure):
 *    Long userId = SecurityUtils.getCurrentUserId()
 *    → We read userId from the JWT token the server already validated.
 *    → No header needed. No way to fake it.
 *
 *  Also added: @Tag and @Operation annotations for Swagger docs.
 *  These appear as descriptions in the API documentation page.
 */
@Slf4j
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Create, monitor, retry, and cancel AI processing jobs")
public class JobController {

    private final JobService       jobService;
    private final PdfTextExtractor pdfExtractor;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    // ── CREATE ────────────────────────────────────────────────────

    @Operation(
        summary = "Create a new AI job",
        description = "Submit text payload or PDF file for AI processing. " +
                      "If a PDF is uploaded, text is extracted and stored separately from the question."
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createJob(
            @RequestParam("jobType")                                    String jobType,
            @RequestParam(value = "payload",     required = false,
                          defaultValue = "")                            String payload,
            @RequestParam(value = "file",        required = false)      MultipartFile file,
            @RequestParam(value = "file2",       required = false)      MultipartFile file2,
            @RequestParam(value = "scheduledAt", required = false)      String scheduledAt,
            @RequestParam(value = "aiModel",     required = false)      String aiModel,
            @RequestParam(value = "priority",    required = false,
                          defaultValue = "MEDIUM")                      String priority
    ) {
        // SECURITY FIX: read userId from JWT token, not from browser header
        Long userId = SecurityUtils.getCurrentUserId();

        boolean hasPayload = payload != null && !payload.isBlank();
        boolean hasFile    = file    != null && !file.isEmpty();
        boolean hasFile2   = file2 != null && !file2.isEmpty();

        if ("COMPARE_DOCUMENTS".equalsIgnoreCase(jobType)) {
            if (!hasFile || !hasFile2) {
                return ResponseEntity.badRequest()
                    .body("Please upload both documents for comparison.");
            }
        } else {
            if (!hasPayload && !hasFile) {
                return ResponseEntity.badRequest()
                    .body("Either 'payload' text or a file must be provided.");
            }
        }
        if (!hasPayload && !hasFile) {
            return ResponseEntity.badRequest()
                    .body("Either 'payload' text or a file must be provided.");
        }

        String filePath     = null;
        String documentText = null;

        if ("COMPARE_DOCUMENTS".equalsIgnoreCase(jobType)) {
            try {
                String text1 = pdfExtractor.extract(file);
                String text2 = pdfExtractor.extract(file2);

                payload =
                    "---DOCUMENT 1---\n" + text1 +
                    "\n---DOCUMENT 2---\n" + text2;

                log.info("Compare docs prepared: doc1={} chars, doc2={} chars",
                        text1.length(), text2.length());

            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to process documents: " + e.getMessage());
            }

        } else if (hasFile) {
            try {
                filePath = saveFile(file);
                documentText = pdfExtractor.extract(file);

                log.info("PDF extracted: {} chars for job type '{}'",
                        documentText.length(), jobType);

            } catch (Exception e) {
                log.warn("PDF extraction failed: {}", e.getMessage());
            }
        }

        LocalDateTime scheduledDateTime = null;
        if (scheduledAt != null && !scheduledAt.isBlank()) {
            try {
                scheduledDateTime = LocalDateTime.parse(scheduledAt);
            } catch (Exception e) {
                log.warn("Invalid scheduledAt '{}' — running immediately", scheduledAt);
            }
        }

        Job created = jobService.createJob(
                userId, jobType, payload, filePath,
                documentText, scheduledDateTime, aiModel, priority);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── READ ──────────────────────────────────────────────────────

    @Operation(summary = "Get all jobs for current user", description = "Optionally filter by status: PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, SCHEDULED")
    @GetMapping
    public ResponseEntity<List<Job>> getJobs(
            @RequestParam(required = false) String status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(jobService.getJobsByStatusForUser(userId, status));
    }

    @Operation(summary = "Get a specific job by ID")
    @GetMapping("/{jobId}")
    public ResponseEntity<Job> getJobById(@PathVariable Long jobId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(jobService.getJobByIdForUser(jobId, userId));
    }

    // ── ACTIONS ───────────────────────────────────────────────────

    @Operation(summary = "Retry a failed or cancelled job")
    @PostMapping("/{jobId}/retry")
    public ResponseEntity<Job> retryJob(@PathVariable Long jobId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(jobService.retryJob(jobId, userId));
    }

    @Operation(summary = "Cancel a pending or scheduled job")
    @PutMapping("/{jobId}/cancel")
    public ResponseEntity<Job> cancelJob(@PathVariable Long jobId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(jobService.cancelJob(jobId, userId));
    }

    // ── USER JOBS (admin / public path) ───────────────────────────

    @Operation(summary = "Get all jobs for a specific userId (admin use)")
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Job>> getUserJobs(@PathVariable Long userId) {
        return ResponseEntity.ok(jobService.getUserJobs(userId));
    }

    // ── FILE SAVE ─────────────────────────────────────────────────

    private String saveFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
        String fileName    = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path   destination = uploadPath.resolve(fileName);
        file.transferTo(destination);
        log.debug("File saved: {}", destination);
        return destination.toString().replace("\\", "/");
    }
}