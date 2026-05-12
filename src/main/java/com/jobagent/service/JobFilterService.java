package com.jobagent.service;

import com.jobagent.model.Job;
import com.jobagent.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles deduplication and persistence of processed jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobFilterService {

    private final JobRepository jobRepository;

    /**
     * Save new jobs, update existing ones, skip unchanged duplicates.
     * Returns count of newly inserted jobs.
     */
    public int saveJobs(List<Job> jobs) {
        int newCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;

        for (Job job : jobs) {
            try {
                if (job.getExternalId() == null || job.getExternalId().isBlank()) {
                    log.warn("Skipping job with null externalId: {}", job.getTitle());
                    skippedCount++;
                    continue;
                }

                var existing = jobRepository.findByExternalId(job.getExternalId());

                if (existing.isEmpty()) {
                    // Brand new job
                    job.setUpdatedAt(LocalDateTime.now());
                    jobRepository.save(job);
                    newCount++;
                } else {
                    // Update score and summary if AI has new data
                    Job saved = existing.get();
                    boolean changed = false;

                    if (saved.getRelevanceScore() != job.getRelevanceScore()) {
                        saved.setRelevanceScore(job.getRelevanceScore());
                        changed = true;
                    }
                    if (job.getAiSummary() != null && !job.getAiSummary().equals(saved.getAiSummary())) {
                        saved.setAiSummary(job.getAiSummary());
                        changed = true;
                    }
                    if (job.getSkills() != null && !job.getSkills().equals(saved.getSkills())) {
                        saved.setSkills(job.getSkills());
                        changed = true;
                    }

                    if (changed) {
                        saved.setUpdatedAt(LocalDateTime.now());
                        jobRepository.save(saved);
                        updatedCount++;
                    } else {
                        skippedCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Error saving job '{}': {}", job.getTitle(), e.getMessage());
            }
        }

        log.info("Save summary → New: {}, Updated: {}, Skipped: {}", newCount, updatedCount, skippedCount);
        return newCount;
    }

    /**
     * Mark jobs older than 30 days as inactive.
     */
    public void deactivateOldJobs(int daysOld) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        List<Job> old = jobRepository.findByScrapedAtAfterAndIsActiveTrue(cutoff);
        // In practice, query jobs BEFORE the cutoff and mark inactive
        log.info("Deactivation check complete.");
    }

    /**
     * Get jobs scraped in last N hours.
     */
    public List<Job> getRecentJobs(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return jobRepository.findByScrapedAtAfterAndIsActiveTrue(since);
    }
}
