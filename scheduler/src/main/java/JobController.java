package com.example.scheduler;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobController {
    private final Queue<Job> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, JobStatus> status = new ConcurrentHashMap<>();

    // Submit job
    @PostMapping("/jobs")
    public String submit(@RequestParam String imagePath) {
        String id = UUID.randomUUID().toString();
        queue.add(new Job(id, imagePath));
        status.put(id, JobStatus.QUEUED);
        return id;
    }

    // Workers pull next job
    @GetMapping("/jobs/next")
    public ResponseEntity<Job> next() {
        Job job = queue.poll();
        if (job == null) return ResponseEntity.noContent().build();
        status.put(job.id(), JobStatus.IN_PROGRESS);
        return ResponseEntity.ok(job);
    }

    // Mark complete
    @PostMapping("/jobs/{id}/complete")
    public void complete(@PathVariable String id) {
        status.put(id, JobStatus.COMPLETED);
    }

    // Query status
    @GetMapping("/jobs/{id}/status")
    public JobStatus status(@PathVariable String id) {
        return status.getOrDefault(id, JobStatus.UNKNOWN);
    }
}