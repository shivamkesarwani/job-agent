# Job Agent – AI-Powered Job Scraper (Spring Boot + MongoDB)

An AI agent that scrapes job portals twice daily, scores each listing against your skills using an LLM, and exposes a REST API to browse the results.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Spring Boot App                    │
│                                                     │
│  JobScheduler (8 AM / 8 PM)                        │
│       │                                             │
│       ▼                                             │
│  JobScraperService ──► Remotive API                │
│       │             ──► RemoteOK API               │
│       │             ──► Arbeitnow API              │
│       │             ──► LinkedIn HTML              │
│       │                                             │
│       ▼                                             │
│  AIAgentService ──► OpenAI GPT-4o-mini             │
│  (score 0-10, extract skills, summarize)           │
│       │                                             │
│       ▼                                             │
│  JobFilterService ──► MongoDB (deduplicate + save) │
│                                                     │
│  JobController ──► REST API                        │
└─────────────────────────────────────────────────────┘
```

---

## Prerequisites

- Java 17+
- Maven 3.8+
- MongoDB (local or Atlas)
- OpenAI API key (or swap for any Spring AI provider)

---

## Setup

### 1. Clone and configure

Edit `src/main/resources/application.yml`:

```yaml
job-agent:
  candidate:
    skills:
      - Java
      - Spring Boot
      - MongoDB
    experience-years: 3
    preferred-roles:
      - Backend Developer
      - Java Developer
    preferred-locations:
      - Remote
      - Delhi
```

### 2. Set your OpenAI key

```bash
export OPENAI_API_KEY=sk-...
```

Or add it directly in `application.yml` (not recommended for production).

### 3. Run MongoDB

```bash
# Local Docker
docker run -d -p 27017:27017 --name mongo mongo:7

# Or use MongoDB Atlas (free tier) and update the URI in application.yml
```

### 4. Build and run

```bash
mvn clean install
mvn spring-boot:run
```

---

## REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/jobs` | All active jobs, sorted by AI relevance score |
| GET | `/api/jobs?minScore=7.0&page=0&size=20` | Filter by minimum score |
| GET | `/api/jobs/{id}` | Single job detail |
| GET | `/api/jobs/search?q=spring+boot` | Full-text search |
| GET | `/api/jobs/source/remotive` | Jobs from a specific portal |
| GET | `/api/jobs/recent?hours=24` | Jobs scraped in last N hours |
| GET | `/api/jobs/stats` | Count of jobs per portal |
| POST | `/api/jobs/trigger` | Manually trigger a full agent run |

### Example response (`GET /api/jobs`)

```json
{
  "content": [
    {
      "id": "665abc123...",
      "title": "Senior Java Backend Developer",
      "company": "Acme Corp",
      "location": "Remote",
      "source": "remotive",
      "relevanceScore": 9.2,
      "aiSummary": "Strong match: requires Spring Boot, MongoDB, and microservices experience.",
      "skills": ["Java", "Spring Boot", "MongoDB", "Docker"],
      "applyUrl": "https://remotive.com/job/...",
      "scrapedAt": "2025-01-15T08:02:11"
    }
  ],
  "totalElements": 47,
  "totalPages": 3
}
```

---

## Customization

### Add a new job portal

1. Add a new method in `JobScraperService.java`
2. Call it from `scrapeAllPortals()`
3. Add config in `application.yml`

### Change the AI model

In `application.yml`, change:
```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-4o-mini   # or gpt-4o, or use Anthropic/Gemini via Spring AI
```

### Use Anthropic Claude instead of OpenAI

Replace the dependency in `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

And in `application.yml`:
```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-20250514
```

---

## Scheduler

The agent runs automatically:
- **8:00 AM** every day (morning scan)
- **8:00 PM** every day (evening scan)

You can also trigger it manually via `POST /api/jobs/trigger`.

---

## MongoDB Indexes (auto-created)

- `externalId` – unique, prevents duplicates
- `source` – fast portal filtering
- `relevanceScore` – fast score-based sorting
- `scrapedAt` – fast recent job queries
