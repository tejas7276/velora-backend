package com.velora.aijobflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processing_time")
    private Long processingTime;

    @Column(name = "file_path")
    private String filePath;

    // Extracted document text — stored at upload time for AI processing
    @Column(name = "document_text", columnDefinition = "TEXT")
    private String documentText;

    // Which Groq model to use — null = backend default
    @Column(name = "ai_model")
    private String aiModel;

    // Priority: LOW / MEDIUM / HIGH / CRITICAL
    @Column(name = "priority")
    private String priority = "MEDIUM";

    // Scheduled execution time — null = run immediately
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @JsonIgnore
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Attachment> attachments = new ArrayList<>();
}