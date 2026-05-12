package com.jobagent.controller;

import com.jobagent.model.Job;
import com.jobagent.repository.JobRepository;
import com.jobagent.scheduler.JobScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API to query the job database and manually trigger the agent.
 *
 * Endpoints:
 *   GET  /api/jobs               – All active jobs, sorted by relevance
 *   GET  /api/jobs/{id}          – Single job by MongoDB ID
 *   GET  /api/jobs/search        – Full-text search
 *   GET  /api/jobs/source/{src}  – Filter by portal (linkedin, remotive…)
 *   GET  /api/jobs/recent        – Jobs scraped in last 24h
 *   GET  /api/jobs/stats         – Counts per source
 *   POST /api/jobs/trigger       – Manually trigger a full agent run
 */
@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JobController {

    private final JobRepository jobRepository;
    private final JobScheduler jobScheduler;

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs
    // ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<Job>> getAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "5.0") double minScore) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "relevanceScore"));

        Page<Job> jobs = minScore > 0
                ? jobRepository.findByRelevanceScoreGreaterThanEqualAndIsActiveTrue(minScore, pageable)
                : jobRepository.findByIsActiveTrueOrderByRelevanceScoreDesc(pageable);

        return ResponseEntity.ok(jobs);
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs/{id}
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJobById(@PathVariable String id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs/search?q=spring+boot
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<Page<Job>> searchJobs(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "relevanceScore"));

        return ResponseEntity.ok(jobRepository.searchJobs(q, pageable));
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs/source/remotive
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/source/{source}")
    public ResponseEntity<Page<Job>> getBySource(
            @PathVariable String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "relevanceScore"));

        return ResponseEntity.ok(jobRepository.findBySourceAndIsActiveTrue(source, pageable));
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs/recent
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/recent")
    public ResponseEntity<List<Job>> getRecentJobs(
            @RequestParam(defaultValue = "24") int hours) {

        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return ResponseEntity.ok(jobRepository.findByScrapedAtAfterAndIsActiveTrue(since));
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/jobs/stats
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long total = jobRepository.countBySource("remotive")
                + jobRepository.countBySource("remoteok")
                + jobRepository.countBySource("arbeitnow")
                + jobRepository.countBySource("linkedin");

        return ResponseEntity.ok(Map.of(
                "total", total,
                "bySource", Map.of(
                        "remotive", jobRepository.countBySource("remotive"),
                        "remoteok", jobRepository.countBySource("remoteok"),
                        "arbeitnow", jobRepository.countBySource("arbeitnow"),
                        "linkedin", jobRepository.countBySource("linkedin")
                ),
                "lastUpdated", LocalDateTime.now().toString()
        ));
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/jobs/trigger  – Manual agent run
    // ─────────────────────────────────────────────────────────────

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerAgentRun() {
        log.info("Manual agent run triggered via API");
        JobScheduler.AgentRunResult result = jobScheduler.runAgentPipeline();

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "scraped", result.scraped(),
                "passed", result.passed(),
                "saved", result.saved(),
                "durationMs", result.durationMs()
        ));
    }
}
