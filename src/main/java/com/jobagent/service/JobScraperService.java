package com.jobagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrapes job listings from multiple portals.
 *
 * Supported sources:
 *  1. Remotive API      – fully public JSON API, no auth needed
 *  2. RemoteOK API      – fully public JSON API, no auth needed
 *  3. Arbeitnow API     – fully public JSON API, no auth needed
 *  4. LinkedIn HTML     – HTML scraping (may require proxy/cookie in production)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobScraperService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${job-agent.portals.remotive.base-url}")
    private String remotiveUrl;

    @Value("${job-agent.portals.remoteok.base-url}")
    private String remoteokUrl;

    @Value("${job-agent.portals.arbeitnow.base-url}")
    private String arbeitnowUrl;

    @Value("${job-agent.portals.linkedin.base-url}")
    private String linkedinUrl;

    @Value("${job-agent.portals.remotive.enabled:true}")
    private boolean remotiveEnabled;

    @Value("${job-agent.portals.remoteok.enabled:true}")
    private boolean remoteokEnabled;

    @Value("${job-agent.portals.arbeitnow.enabled:true}")
    private boolean arbeitnowEnabled;

    @Value("${job-agent.portals.linkedin.enabled:false}")
    private boolean linkedinEnabled;

    // ─────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────

    public List<Job> scrapeAllPortals(List<String> keywords) {
        List<Job> allJobs = new ArrayList<>();

        if (remotiveEnabled) {
            log.info("Scraping Remotive...");
            allJobs.addAll(scrapeRemotive(keywords));
        }
        if (remoteokEnabled) {
            log.info("Scraping RemoteOK...");
            allJobs.addAll(scrapeRemoteOK(keywords));
        }
        if (arbeitnowEnabled) {
            log.info("Scraping Arbeitnow...");
            allJobs.addAll(scrapeArbeitnow());
        }
        if (linkedinEnabled) {
            log.info("Scraping LinkedIn (HTML)...");
            keywords.forEach(kw -> allJobs.addAll(scrapeLinkedIn(kw)));
        }

        log.info("Total jobs scraped: {}", allJobs.size());
        return allJobs;
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Remotive  – https://remotive.com/api/remote-jobs
    // ─────────────────────────────────────────────────────────────

    public List<Job> scrapeRemotive(List<String> keywords) {
        List<Job> jobs = new ArrayList<>();

        for (String keyword : keywords) {
            String url = remotiveUrl + "?search=" + keyword + "&limit=50";
            try {
                String json = get(url);
                JsonNode root = objectMapper.readTree(json);
                JsonNode jobsNode = root.path("jobs");

                for (JsonNode node : jobsNode) {
                    Job job = Job.builder()
                            .externalId("remotive-" + node.path("id").asText())
                            .title(node.path("title").asText())
                            .company(node.path("company_name").asText())
                            .location(node.path("candidate_required_location").asText("Remote"))
                            .description(cleanHtml(node.path("description").asText()))
                            .applyUrl(node.path("url").asText())
                            .source("remotive")
                            .jobType(node.path("job_type").asText())
                            .salary(node.path("salary").asText(""))
                            .isActive(true)
                            .scrapedAt(LocalDateTime.now())
                            .postedAt(LocalDateTime.now())
                            .build();

                    jobs.add(job);
                }
                log.info("Remotive: scraped {} jobs for keyword '{}'", jobs.size(), keyword);

            } catch (Exception e) {
                log.error("Error scraping Remotive for keyword '{}': {}", keyword, e.getMessage());
            }
        }
        return jobs;
    }

    // ─────────────────────────────────────────────────────────────
    // 2. RemoteOK – https://remoteok.com/api
    // ─────────────────────────────────────────────────────────────

    public List<Job> scrapeRemoteOK(List<String> keywords) {
        List<Job> jobs = new ArrayList<>();
        String keywordParam = String.join(",", keywords);
        String url = remoteokUrl + "?tags=" + keywordParam;

        try {
            String json = get(url);
            JsonNode root = objectMapper.readTree(json);

            // First element is metadata — skip it
            for (int i = 1; i < root.size(); i++) {
                JsonNode node = root.get(i);
                if (node.has("id") && node.has("position")) {
                    Job job = Job.builder()
                            .externalId("remoteok-" + node.path("id").asText())
                            .title(node.path("position").asText())
                            .company(node.path("company").asText())
                            .location("Remote")
                            .description(cleanHtml(node.path("description").asText()))
                            .applyUrl(node.path("url").asText())
                            .source("remoteok")
                            .jobType("Full-time")
                            .salary(node.path("salary").asText(""))
                            .isActive(true)
                            .scrapedAt(LocalDateTime.now())
                            .postedAt(LocalDateTime.now())
                            .build();
                    jobs.add(job);
                }
            }
            log.info("RemoteOK: scraped {} jobs", jobs.size());

        } catch (Exception e) {
            log.error("Error scraping RemoteOK: {}", e.getMessage());
        }
        return jobs;
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Arbeitnow – https://www.arbeitnow.com/api/job-board-api
    // ─────────────────────────────────────────────────────────────

    public List<Job> scrapeArbeitnow() {
        List<Job> jobs = new ArrayList<>();

        try {
            String json = get(arbeitnowUrl);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");

            for (JsonNode node : data) {
                Job job = Job.builder()
                        .externalId("arbeitnow-" + node.path("slug").asText())
                        .title(node.path("title").asText())
                        .company(node.path("company_name").asText())
                        .location(node.path("location").asText("Remote"))
                        .description(cleanHtml(node.path("description").asText()))
                        .applyUrl(node.path("url").asText())
                        .source("arbeitnow")
                        .jobType(node.path("remote").asBoolean() ? "Remote" : "On-site")
                        .salary("")
                        .isActive(true)
                        .scrapedAt(LocalDateTime.now())
                        .postedAt(LocalDateTime.now())
                        .build();
                jobs.add(job);
            }
            log.info("Arbeitnow: scraped {} jobs", jobs.size());

        } catch (Exception e) {
            log.error("Error scraping Arbeitnow: {}", e.getMessage());
        }
        return jobs;
    }

    // ─────────────────────────────────────────────────────────────
    // 4. LinkedIn – HTML scraping (public search, no login needed)
    //    Note: LinkedIn may block scrapers in production.
    //    Use rotating proxies or RapidAPI LinkedIn adapter for reliability.
    // ─────────────────────────────────────────────────────────────

    public List<Job> scrapeLinkedIn(String keyword) {
        List<Job> jobs = new ArrayList<>();
        String url = linkedinUrl + "?keywords=" + keyword.replace(" ", "%20")
                + "&location=India&f_WT=2"; // f_WT=2 = Remote filter

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get();

            Elements cards = doc.select("div.base-card");

            for (Element card : cards) {
                String title = card.select("h3.base-search-card__title").text();
                String company = card.select("h4.base-search-card__subtitle").text();
                String location = card.select("span.job-search-card__location").text();
                String link = card.select("a.base-card__full-link").attr("href");
                String jobId = card.attr("data-entity-urn")
                        .replace("urn:li:jobPosting:", "");

                if (title.isEmpty()) continue;

                Job job = Job.builder()
                        .externalId("linkedin-" + jobId)
                        .title(title)
                        .company(company)
                        .location(location)
                        .description("")        // Fetched lazily or via detail page
                        .applyUrl(link)
                        .source("linkedin")
                        .jobType("Full-time")
                        .isActive(true)
                        .scrapedAt(LocalDateTime.now())
                        .postedAt(LocalDateTime.now())
                        .build();

                jobs.add(job);
            }
            log.info("LinkedIn: scraped {} jobs for keyword '{}'", jobs.size(), keyword);

        } catch (IOException e) {
            log.error("Error scraping LinkedIn for keyword '{}': {}", keyword, e.getMessage());
        }
        return jobs;
    }

    // ─────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────

    private String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response.code() + " for " + url);
            }
            return response.body().string();
        }
    }

    private String cleanHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.parse(html).text();
    }
}
