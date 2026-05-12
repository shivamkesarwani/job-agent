package com.jobagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "jobs")
public class Job {

    @Id
    private String id;

    @Indexed(unique = true)
    private String externalId;           // Unique ID from the source portal

    private String title;
    private String company;
    private String location;
    private String description;
    private String applyUrl;

    private String source;               // e.g., "linkedin", "remotive", "remoteok"
    private String jobType;              // Full-time, Part-time, Contract
    private String experienceLevel;      // Junior, Mid, Senior

    private List<String> skills;         // Extracted skills from description
    private String salary;               // Raw salary string from portal

    private double relevanceScore;       // 0.0 - 10.0 (AI-assigned score)
    private String aiSummary;            // Short AI-generated summary

    private boolean isActive;
    private LocalDateTime postedAt;
    private LocalDateTime scrapedAt;
    private LocalDateTime updatedAt;
}
