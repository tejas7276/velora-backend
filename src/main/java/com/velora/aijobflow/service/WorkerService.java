package com.velora.aijobflow.service;

import com.velora.aijobflow.dto.WorkerHeartbeatRequest;
import com.velora.aijobflow.exception.WorkerNotFoundException;
import com.velora.aijobflow.model.Worker;
import com.velora.aijobflow.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerRepository workerRepository;

    @Transactional
    public Worker registerWorker(String name) {
        // Re-activate if the worker name already exists (restart scenario)
        return workerRepository.findByName(name)
                .map(existing -> {
                    existing.setStatus("ACTIVE");
                    existing.setLastHeartbeat(LocalDateTime.now());
                    existing.setActiveJobs(0);
                    log.info("Re-activated existing worker '{}'", name);
                    return workerRepository.save(existing);
                })
                .orElseGet(() -> {
                    Worker worker = new Worker();
                    worker.setName(name);
                    worker.setStatus("ACTIVE");
                    worker.setLastHeartbeat(LocalDateTime.now());
                    worker.setActiveJobs(0);
                    log.info("Registered new worker '{}'", name);
                    return workerRepository.save(worker);
                });
    }

    @Transactional
    public void heartbeat(WorkerHeartbeatRequest request) {
        Worker worker = workerRepository.findByName(request.getWorkerName())
                .orElseThrow(() -> new WorkerNotFoundException(
                        "Worker not found: " + request.getWorkerName()));

        worker.setLastHeartbeat(LocalDateTime.now());
        worker.setActiveJobs(request.getActiveJobs());
        worker.setCpuUsage(request.getCpuUsage());
        worker.setRamUsage(request.getRamUsage());
        worker.setStatus("ACTIVE");

        workerRepository.save(worker);
    }

    @Transactional(readOnly = true)
    public List<Worker> getAllWorkers() {
        return workerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public long getActiveWorkerCount() {
        return workerRepository.countByStatus("ACTIVE");
    }

    /**
     * Called by a scheduler to mark workers that have not sent a heartbeat
     * in the last 30 seconds as INACTIVE. Keeps the metrics accurate.
     */
    @Transactional
    public void markStaleWorkersInactive() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<Worker> stale = workerRepository.findByStatusAndLastHeartbeatBefore("ACTIVE", cutoff);
        stale.forEach(w -> {
            w.setStatus("INACTIVE");
            log.warn("Worker '{}' marked INACTIVE (last heartbeat: {})", w.getName(), w.getLastHeartbeat());
        });
        if (!stale.isEmpty()) {
            workerRepository.saveAll(stale);
        }
    }
}
