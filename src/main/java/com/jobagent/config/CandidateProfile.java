package com.jobagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "job-agent.candidate")
public class CandidateProfile {

    private List<String> skills;
    private int experienceYears;
    private List<String> preferredRoles;
    private List<String> preferredLocations;
    private long minSalary;
}
