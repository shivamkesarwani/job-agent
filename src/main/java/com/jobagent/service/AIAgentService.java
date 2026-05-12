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

        log.info("AI Agent processing {} jobs...", rawJobs.size());

        for (Job job : rawJobs) {
            try {
                Job enriched = analyzeJob(chatClient, job);
                if (enriched.getRelevanceScore() >= MIN_RELEVANCE_SCORE) {
                    processedJobs.add(enriched);
                    log.debug("Job '{}' at '{}' scored {}", job.getTitle(), job.getCompany(), enriched.getRelevanceScore());
                } else {
                    log.debug("Job '{}' at '{}' filtered out (score: {})", job.getTitle(), job.getCompany(), enriched.getRelevanceScore());
                }
            } catch (Exception e) {
                log.error("AI analysis failed for job '{}': {}", job.getTitle(), e.getMessage());
                // Fallback: include job with rule-based score
                job.setRelevanceScore(ruleBasedScore(job));
                job.setAiSummary("Auto-scraped: " + job.getTitle() + " at " + job.getCompany());
                if (job.getRelevanceScore() >= MIN_RELEVANCE_SCORE) {
                    processedJobs.add(job);
                }
            }
        }

        log.info("AI Agent: {} / {} jobs passed relevance filter", processedJobs.size(), rawJobs.size());
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
            You are a job matching assistant. Analyze the following job and evaluate it for a candidate.

            === CANDIDATE PROFILE ===
            Skills: %s
            Years of Experience: %d
            Preferred Roles: %s
            Preferred Locations: %s

            === JOB LISTING ===
            Title: %s
            Company: %s
            Location: %s
            Job Type: %s
            Description: %s

            === YOUR TASK ===
            Respond ONLY in this exact JSON format (no extra text, no markdown):
            {
              "relevanceScore": <number 0.0–10.0>,
              "skills": [<list of tech skills mentioned in job>],
              "experienceLevel": "<Junior|Mid|Senior|Lead>",
              "summary": "<2-sentence summary of what this role is and why it matches or doesn't match the candidate>"
            }
            """,
                String.join(", ", candidateProfile.getSkills()),
                candidateProfile.getExperienceYears(),
                String.join(", ", candidateProfile.getPreferredRoles()),
                String.join(", ", candidateProfile.getPreferredLocations()),
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                job.getJobType() != null ? job.getJobType() : "Not specified",
                truncate(job.getDescription(), 1500)
        );
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

        for (String skill : candidateProfile.getSkills()) {
            if (text.contains(skill.toLowerCase())) score += 1.0;
        }
        for (String role : candidateProfile.getPreferredRoles()) {
            if (text.contains(role.toLowerCase())) score += 1.5;
        }
        for (String loc : candidateProfile.getPreferredLocations()) {
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
