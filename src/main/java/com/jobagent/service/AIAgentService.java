package com.jobagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.CandidateProfile;
import com.jobagent.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Agent core — uses an LLM to:
 *  1. Score each job's relevance to the candidate's profile (0-10)
 *  2. Extract skill tags from the description
 *  3. Generate a short human-readable summary
 *  4. Determine experience level
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIAgentService {

    private final ChatClient.Builder chatClientBuilder;
    private final CandidateProfile candidateProfile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Minimum score to save a job
    private static final double MIN_RELEVANCE_SCORE = 5.0;

    // ─────────────────────────────────────────────────────────────
    // Main entry: process a batch of raw scraped jobs
    // ─────────────────────────────────────────────────────────────

    public List<Job> processJobs(List<Job> rawJobs) {
        List<Job> processedJobs = new ArrayList<>();
        ChatClient chatClient = chatClientBuilder.build();

        // ── Limit jobs to analyze ──────────────────────────
        int maxJobs = candidateProfile.getLimits().getMaxJobsPerRun();
        List<Job> jobsToProcess = rawJobs.size() > maxJobs
                ? rawJobs.subList(0, maxJobs)
                : rawJobs;

        log.info("AI Agent processing {} / {} jobs (limit: {})",
                jobsToProcess.size(), rawJobs.size(), maxJobs);

        for (int i = 0; i < jobsToProcess.size(); i++) {
            Job job = jobsToProcess.get(i);
            try {
                Job enriched = analyzeJob(chatClient, job);
                if (enriched.getRelevanceScore() >= MIN_RELEVANCE_SCORE) {
                    processedJobs.add(enriched);
                }

                // ── Delay between Gemini calls to avoid 429 ──
                long delay = candidateProfile.getLimits().getDelayBetweenCallsMs();
                if (i < jobsToProcess.size() - 1 && delay > 0) {
                    log.debug("Waiting {}ms before next Gemini call...", delay);
                    Thread.sleep(delay);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Processing interrupted at job {}", i);
                break;
            } catch (Exception e) {
                log.error("AI analysis failed for '{}': {}", job.getTitle(), e.getMessage());
                // Fallback to rule-based score
                job.setRelevanceScore(ruleBasedScore(job));
                job.setAiSummary("Auto-scored: " + job.getTitle() + " at " + job.getCompany());
                if (job.getRelevanceScore() >= MIN_RELEVANCE_SCORE) {
                    processedJobs.add(job);
                }
            }
        }

        log.info("AI Agent done: {} jobs passed filter", processedJobs.size());
        return processedJobs;
    }

    // ─────────────────────────────────────────────────────────────
    // Analyze a single job with LLM
    // ─────────────────────────────────────────────────────────────

    private Job analyzeJob(ChatClient chatClient, Job job) {
        String prompt = buildPrompt(job);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseAIResponse(job, response);
    }

    private String buildPrompt(Job job) {
        return String.format("""
            Score this job for the candidate. Respond ONLY in JSON.

        Candidate skills: %s (%d yrs exp)
        Preferred roles: %s

        Job: %s at %s (%s)
        Description (brief): %s

        JSON format:
        {"relevanceScore":<0.0-10.0>,"skills":[<top 5 skills>],"experienceLevel":"<Junior|Mid|Senior>","summary":"<1 sentence>"}
        """,
                String.join(", ", candidateProfile.getCandidate().getSkills()),
                candidateProfile.getCandidate().getExperienceYears(),
                String.join(", ", candidateProfile.getCandidate().getPreferredRoles()),
                escapeForFormat(job.getTitle()),
                escapeForFormat(job.getCompany()),
                escapeForFormat(job.getLocation()),
                truncate(job.getDescription(), 300)
        );
    }

    private String escapeForFormat(String input) {
        if (input == null) return "";
        return input.replace("%", "%%");
    }

    private Job parseAIResponse(Job job, String response) {
        try {
            // Strip any accidental markdown fences
            String clean = response.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode node = objectMapper.readTree(clean);

            job.setRelevanceScore(node.path("relevanceScore").asDouble(0.0));
            job.setAiSummary(node.path("summary").asText(""));
            job.setExperienceLevel(node.path("experienceLevel").asText("Mid"));

            List<String> skills = new ArrayList<>();
            node.path("skills").forEach(s -> skills.add(s.asText()));
            job.setSkills(skills);

        } catch (Exception e) {
            log.warn("Failed to parse AI JSON response for '{}', using fallback", job.getTitle());
            job.setRelevanceScore(ruleBasedScore(job));
            job.setAiSummary(job.getTitle() + " at " + job.getCompany());
            job.setSkills(extractSkillsSimple(job.getDescription()));
        }
        return job;
    }

    // ─────────────────────────────────────────────────────────────
    // Rule-based fallback scorer (no LLM needed)
    // ─────────────────────────────────────────────────────────────

    private double ruleBasedScore(Job job) {
        double score = 0.0;
        String text = (job.getTitle() + " " + job.getDescription()).toLowerCase();

        for (String skill : candidateProfile.getCandidate().getSkills()) {
            if (text.contains(skill.toLowerCase())) score += 1.0;
        }
        for (String role : candidateProfile.getCandidate().getPreferredRoles()) {
            if (text.contains(role.toLowerCase())) score += 1.5;
        }
        for (String loc : candidateProfile.getCandidate().getPreferredLocations()) {
            if (text.contains(loc.toLowerCase())) score += 0.5;
        }
        return Math.min(score, 10.0);
    }

    private List<String> extractSkillsSimple(String description) {
        if (description == null) return List.of();
        String lower = description.toLowerCase();
        List<String> known = Arrays.asList(
                "java", "spring", "spring boot", "microservices", "rest", "mongodb",
                "sql", "docker", "kubernetes", "aws", "kafka", "redis", "git",
                "hibernate", "jpa", "maven", "gradle", "junit", "react", "angular"
        );
        return known.stream().filter(lower::contains).collect(Collectors.toList());
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() > maxChars ? text.substring(0, maxChars) + "..." : text;
    }
}
