package com.velora.aijobflow.controller;

import com.velora.aijobflow.dto.WorkerHeartbeatRequest;
import com.velora.aijobflow.model.Worker;
import com.velora.aijobflow.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerService workerService;

    @PostMapping("/register")
    public ResponseEntity<Worker> registerWorker(@RequestParam String name) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workerService.registerWorker(name));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody WorkerHeartbeatRequest request) {
        workerService.heartbeat(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<Worker>> getAllWorkers() {
        return ResponseEntity.ok(workerService.getAllWorkers());
    }
}
