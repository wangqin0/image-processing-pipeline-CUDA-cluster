package com.example.scheduler;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@RestController
public class JobController {
    private static final Logger logger = LoggerFactory.getLogger(JobController.class);
    private final Queue<Job> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, JobStatus> status = new ConcurrentHashMap<>();
    // Track when each worker last contacted the scheduler
    private final Map<String, Long> workerLastSeen = new ConcurrentHashMap<>();

    // Request-polling interval coming from env var (e.g. "1s", "500ms"). Default to 1s.
    @Value("${WORKER_PULL_INTERVAL:1000}")
    private String workerIntervalStr;
    private long workerIntervalMillis;

    @PostConstruct
    private void initInterval() {
        workerIntervalMillis = parseDurationToMillis(workerIntervalStr);
    }

    @PostConstruct
    private void initOutputDir() {
        String outputDir = System.getenv("OUTPUT_DIR");
        if (outputDir == null) {
            outputDir = "/data/output";
        }
        try {
            if (!Files.exists(Paths.get(outputDir))) {
                Files.createDirectories(Paths.get(outputDir));
            }
        } catch (Exception e) {
            logger.error("Failed to create output directory: {}", outputDir, e);
            throw new RuntimeException("Cannot initialize output directory", e);
        }
        logger.info("Output directory initialized: {}", outputDir);
    }

    private static long parseDurationToMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("WORKER_PULL_INTERVAL must be set and non-empty");
        }

        String s = raw.trim().toLowerCase();

        // Accept plain milliseconds (e.g. "1000"), milliseconds with suffix (e.g. "500ms"), or seconds with suffix (e.g. "1s")
        Pattern pattern = Pattern.compile("^(\\d+)(ms|s)?$");
        java.util.regex.Matcher matcher = pattern.matcher(s);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid WORKER_PULL_INTERVAL format: " + raw + ". Expected positive integer milliseconds (e.g. 1000) or integer with 'ms'/'s' suffix (e.g. 500ms, 1s).");
        }

        long value = Long.parseLong(matcher.group(1));
        if (value <= 0) {
            throw new IllegalArgumentException("WORKER_PULL_INTERVAL must be greater than 0");
        }

        String unit = matcher.group(2);
        if (unit == null || unit.equals("ms")) {
            return value; // already in milliseconds
        } else { // unit is "s"
            return value * 1000;
        }
    }

    // Submit job
    @PostMapping("/jobs")
    public String submit(@RequestParam String imagePath) {
        logger.info("Received job submission for imagePath: {}", imagePath);

        // Validate provided image path before enqueuing a job
        validateImagePath(imagePath);

        String id = UUID.randomUUID().toString();
        logger.debug("Generated job id: {}", id);
        queue.add(new Job(id, imagePath));
        status.put(id, JobStatus.QUEUED);
        return id;
    }

    /**
     * Basic validation to ensure callers provide a reasonable image path.
     * 
     * If any rule is violated, we respond with 400 Bad Request so that callers
     * can correct their input instead of silently failing later in the pipeline.
     */
    private void validateImagePath(String imagePath) {
        // Resolve the path and ensure the underlying file exists, is a regular file and is readable.
        Path path;
        try {
            path = Paths.get(imagePath).toAbsolutePath().normalize();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid imagePath: " + ex.getMessage());
        }

        if (!Files.exists(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File does not exist or is not readable: " + path);
        }
    }

    // Workers pull next job
    @GetMapping("/jobs/next")
    public ResponseEntity<Job> next(HttpServletRequest request) {
        // Record the worker call for health tracking
        String workerId = request.getRemoteAddr();
        workerLastSeen.put(workerId, System.currentTimeMillis());

        logger.debug("Worker requested next job");
        Job job = queue.poll();
        if (job == null) {
            logger.debug("No job available for worker");
            return ResponseEntity.noContent().build();
        }
        logger.debug("Dispatched job id: {}", job.id());
        status.put(job.id(), JobStatus.IN_PROGRESS);
        return ResponseEntity.ok(job);
    }

    // Mark complete
    @PostMapping("/jobs/{id}/complete")
    public void complete(@PathVariable String id) {
        logger.info("Marking job {} as completed", id);
        status.put(id, JobStatus.COMPLETED);
    }

    // Query status
    @GetMapping("/jobs/{id}/status")
    public JobStatus status(@PathVariable String id) {
        logger.info("Status requested for job {}", id);
        return status.getOrDefault(id, JobStatus.UNKNOWN);
    }

    // Health endpoint for workers
    @GetMapping("/workers/health")
    public Map<String, Boolean> workersHealth() {
        long now = System.currentTimeMillis();
        long threshold = workerIntervalMillis * 3;
        return workerLastSeen.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> (now - e.getValue()) <= threshold));
    }
}