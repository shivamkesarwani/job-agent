package com.jobagent.scheduler;

import com.jobagent.config.CandidateProfile;
import com.jobagent.model.Job;
import com.jobagent.service.AIAgentService;
import com.jobagent.service.JobFilterService;
import com.jobagent.service.JobScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Schedules the AI Agent to run at 8 AM and 8 PM every day.
 *
 * Pipeline:
 *   Scrape → AI Filter/Score → Deduplicate → Save to MongoDB
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final JobScraperService scraperService;
    private final AIAgentService aiAgentService;
    private final JobFilterService filterService;
    private final CandidateProfile candidateProfile;

    // ─────────────────────────────────────────────────────────────
    // Scheduled runs
    // ─────────────────────────────────────────────────────────────

    /** Morning run – 8:00 AM every day */
    @Scheduled(cron = "0 0 8 * * *")
    public void morningRun() {
        log.info("=== MORNING JOB SCAN STARTED [{}] ===", LocalDateTime.now());
        runAgentPipeline();
    }

    /** Evening run – 8:00 PM every day */
    @Scheduled(cron = "0 0 20 * * *")
    public void eveningRun() {
        log.info("=== EVENING JOB SCAN STARTED [{}] ===", LocalDateTime.now());
        runAgentPipeline();
    }

    // ─────────────────────────────────────────────────────────────
    // Core pipeline (also callable manually via API)
    // ─────────────────────────────────────────────────────────────

    public AgentRunResult runAgentPipeline() {
        long start = System.currentTimeMillis();
        int scraped = 0, passed = 0, saved = 0;

        try {
            // Step 1 – Build keyword list from candidate's preferred roles + skills
            List<String> keywords = buildKeywords();
            log.info("Searching with keywords: {}", keywords);

            // Step 2 – Scrape all enabled portals
            List<Job> rawJobs = scraperService.scrapeAllPortals(keywords);
            scraped = rawJobs.size();
            log.info("Scraped {} raw jobs", scraped);

            // Step 3 – AI analysis: score, summarize, extract skills
            List<Job> scoredJobs = aiAgentService.processJobs(rawJobs);
            passed = scoredJobs.size();
            log.info("AI filter: {} / {} jobs passed", passed, scraped);

            // Step 4 – Deduplicate and save to MongoDB
            saved = filterService.saveJobs(scoredJobs);
            log.info("Saved {} new jobs to MongoDB", saved);

        } catch (Exception e) {
            log.error("Agent pipeline failed: {}", e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - start;
        log.info("=== AGENT RUN COMPLETE in {}ms | Scraped: {} | Passed: {} | Saved: {} ===",
                duration, scraped, passed, saved);

        return new AgentRunResult(scraped, passed, saved, duration);
    }

    private List<String> buildKeywords() {
        // Combine preferred roles with top skills as search terms
        List<String> keywords = new java.util.ArrayList<>(candidateProfile.getCandidate().getPreferredRoles());
        // Add top skills as keywords too
        candidateProfile.getCandidate().getSkills().stream()
                .limit(3)
                .forEach(keywords::add);
        return keywords;
    }

    // ─────────────────────────────────────────────────────────────
    // Result DTO
    // ─────────────────────────────────────────────────────────────

    public record AgentRunResult(int scraped, int passed, int saved, long durationMs) {}
}
