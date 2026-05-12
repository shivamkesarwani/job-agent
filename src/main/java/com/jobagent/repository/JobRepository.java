package com.jobagent.repository;

import com.jobagent.model.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends MongoRepository<Job, String> {

    Optional<Job> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    // Fetch active jobs sorted by relevance score
    Page<Job> findByIsActiveTrueOrderByRelevanceScoreDesc(Pageable pageable);

    // Filter by source portal
    Page<Job> findBySourceAndIsActiveTrue(String source, Pageable pageable);

    // Filter by minimum relevance score
    Page<Job> findByRelevanceScoreGreaterThanEqualAndIsActiveTrue(double minScore, Pageable pageable);

    // Search by keyword in title or company
    @Query("{ 'isActive': true, $or: [ { 'title': { $regex: ?0, $options: 'i' } }, { 'company': { $regex: ?0, $options: 'i' } }, { 'description': { $regex: ?0, $options: 'i' } } ] }")
    Page<Job> searchJobs(String keyword, Pageable pageable);

    // Jobs scraped today
    List<Job> findByScrapedAtAfterAndIsActiveTrue(LocalDateTime since);

    // Count by source
    long countBySource(String source);

    // Cleanup old inactive jobs
    void deleteByIsActiveFalseAndScrapedAtBefore(LocalDateTime cutoff);
}
