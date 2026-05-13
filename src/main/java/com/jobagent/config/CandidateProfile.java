package com.jobagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "job-agent")
public class CandidateProfile {

    private Candidate candidate;
    private Limits limits = new Limits(); // default values

    @Data
    public static class Candidate {
        private List<String> skills;
        private int experienceYears;
        private List<String> preferredRoles;
        private List<String> preferredLocations;
        private long minSalary;
    }

    @Data
    public static class Limits {
        private int maxJobsPerRun = 10;       // default 10
        private long delayBetweenCallsMs = 2000; // default 2s
    }
}
