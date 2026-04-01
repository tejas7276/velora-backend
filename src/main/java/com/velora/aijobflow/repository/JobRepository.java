package com.velora.aijobflow.repository;

import com.velora.aijobflow.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    // ── User-scoped ───────────────────────────────────────────────────────────

    List<Job> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Job> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, String status);

    // Keep these for backward compatibility with any legacy callers
    List<Job> findByUserId(Long userId);
    List<Job> findByUserIdAndStatus(Long userId, String status);

    // ── System/metrics ────────────────────────────────────────────────────────

    @Query("SELECT j FROM Job j ORDER BY j.createdAt DESC")
    List<Job> findAllOrderByCreatedAtDesc();

    List<Job> findByStatus(String status);

    List<Job> findByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    // ── Scheduler ─────────────────────────────────────────────────────────────

    List<Job> findByStatusAndScheduledAtBefore(String status, LocalDateTime time);
}