package com.velora.aijobflow.repository;

import com.velora.aijobflow.model.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, Long> {

    Optional<Worker> findByName(String name);

    long countByStatus(String status);

    List<Worker> findByStatusAndLastHeartbeatBefore(String status, LocalDateTime cutoff);
}
