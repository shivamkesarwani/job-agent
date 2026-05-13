# Job Agent – AI-Powered Job Scraper (Spring Boot + MongoDB)

An AI agent that scrapes job portals twice daily, scores each listing against your skills using Google Gemini AI, and exposes a REST API to browse the results. Includes a built-in HTML dashboard UI.

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
│  AIAgentService ──► Google Gemini 2.0 Flash Lite   │
│  (score 0-10, extract skills, summarize)           │
│       │                                             │
│       ▼                                             │
│  JobFilterService ──► MongoDB (deduplicate + save) │
│                                                     │
│  JobController ──► REST API                        │
│                                                     │
│  Dashboard UI ──► src/main/resources/static/       │
└─────────────────────────────────────────────────────┘
```

---

## Prerequisites

- Java 17+
- Maven 3.8+
- MongoDB (Docker recommended)
- Google Gemini API key (free from [Google AI Studio](https://aistudio.google.com/app/apikey))

---

## Setup

### 1. Clone and configure

Edit `src/main/resources/application.yml` with your skills and preferences:

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
  limits:
    max-jobs-per-run: 5           # max jobs analyzed per agent run
    delay-between-calls-ms: 5000  # 5s delay between Gemini API calls
```

### 2. Set your Gemini API key

Get your free API key from [https://aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)

**Mac/Linux:**
```bash
export GOOGLE_GEMINI_API_KEY=AIzaSy...
```

**Windows CMD:**
```cmd
set GOOGLE_GEMINI_API_KEY=AIzaSy...
```

**IntelliJ IDEA:**
1. Run → Edit Configurations
2. Select your Spring Boot run config
3. Modify options → Environment Variables
4. Add: `GOOGLE_GEMINI_API_KEY=AIzaSy...`

### 3. Run MongoDB via Docker

```bash
# Start MongoDB container (auto-restarts with Docker)
docker run -d \
  --name mongodb \
  --restart always \
  -p 27017:27017 \
  mongo:7
```

Verify it's running:
```bash
docker ps
```

### 4. Build and run

```bash
mvn clean install
mvn spring-boot:run
```

App starts on `http://localhost:8080`

---

## Dashboard UI

A built-in HTML dashboard is available at:

```
http://localhost:8080
```

Place `index.html` (the dashboard file) in:
```
src/main/resources/static/index.html
```

**Features:**
- View all scraped jobs sorted by AI relevance score
- Search jobs by MongoDB ID
- Filter by source portal (Remotive, RemoteOK, Arbeitnow, LinkedIn)
- Filter by minimum relevance score (slider)
- Full-text search by title, company, or skill
- View full job details with AI summary and skill tags
- One-click Apply button
- Manually trigger agent run from the UI

---

## Viewing Data in MongoDB

### Option 1 — MongoDB Compass (Recommended)
1. Open MongoDB Compass
2. Connect using: `mongodb://localhost:27017`
3. Open the `jobagent` database → `jobs` collection

### Option 2 — MongoDB Shell inside Docker
```bash
# Open shell inside Docker container
docker exec -it mongodb mongosh

# Commands inside mongosh
use jobagent
db.jobs.find().pretty()                              # all jobs
db.jobs.countDocuments()                             # total count
db.jobs.find({ source: "linkedin" }).pretty()        # by portal
db.jobs.find({ relevanceScore: { $gte: 8 } }).pretty() # high score jobs
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

### Manually trigger agent run
```bash
curl -X POST http://localhost:8080/api/jobs/trigger
```

### Example response (`GET /api/jobs`)

```json
{
  "content": [
    {
      "id": "665abc123...",
      "title": "Senior Java Backend Developer",
      "company": "Acme Corp",
      "location": "Remote",
      "source": "linkedin",
      "relevanceScore": 9.2,
      "aiSummary": "Strong match: requires Spring Boot, MongoDB, and microservices experience.",
      "skills": ["Java", "Spring Boot", "MongoDB", "Docker"],
      "applyUrl": "https://linkedin.com/jobs/...",
      "scrapedAt": "2025-01-15T08:02:11"
    }
  ],
  "totalElements": 3,
  "totalPages": 1
}
```

---

## AI Configuration (Google Gemini)

Current setup in `application.yml`:

```yaml
spring:
  ai:
    google:
      genai:
        api-key: ${GOOGLE_GEMINI_API_KEY}
        chat:
          options:
            model: gemini-2.0-flash-lite   # separate free quota pool
            temperature: 0.2
    retry:
      max-attempts: 1          # no retries — saves quota
      on-client-errors: false
```

### Why `gemini-2.0-flash-lite`?
- Has its own separate free quota pool from `gemini-2.0-flash`
- Faster and cheaper per token
- More than capable for job scoring tasks
- Free tier: up to 1,500 requests/day

### Gemini Free Tier Limits & Recommended Settings

| Model | RPM Limit | Safe Delay | Max Jobs/Run |
|---|---|---|---|
| `gemini-2.0-flash-lite` | 30 req/min | `3000ms` | 10 |
| `gemini-2.0-flash` | 15 req/min | `5000ms` | 5 |

If you hit a **429 quota error**, reduce jobs per run or increase delay in `application.yml`:
```yaml
job-agent:
  limits:
    max-jobs-per-run: 5
    delay-between-calls-ms: 5000
```

---

## Maven Dependencies

Key dependency versions used:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
</parent>

<!-- Spring AI BOM — manages all Spring AI versions -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.4</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- Google Gemini integration (no version needed — BOM manages it) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
</dependency>
```

> **Note:** `spring-ai-starter-model-google-genai` requires BOM version `1.1.4` or higher.
> It is NOT available in `1.0.0`.

---

## Customization

### Add a new job portal

1. Add a new scraper method in `JobScraperService.java`
2. Call it from `scrapeAllPortals()`
3. Add toggle config in `application.yml` under `job-agent.portals`

### Switch to a different AI provider

**OpenAI:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
```

**Anthropic Claude:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```
```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-20250514
```

> No changes needed in `AIAgentService.java` — `ChatClient` is provider-agnostic.

---

## Scheduler

The agent runs automatically:
- **8:00 AM** every day (morning scan)
- **8:00 PM** every day (evening scan)

Trigger manually via API or dashboard UI:
```bash
curl -X POST http://localhost:8080/api/jobs/trigger
```

---

## MongoDB Indexes (auto-created)

- `externalId` – unique, prevents duplicate jobs
- `source` – fast portal filtering
- `relevanceScore` – fast score-based sorting
- `scrapedAt` – fast recent job queries

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `429 quota exceeded, limit: 0` | Gemini daily free quota exhausted | Switch to `gemini-2.0-flash-lite` or wait 24h |
| `429 rate limit` | Too many requests per minute | Increase `delay-between-calls-ms` to `5000` |
| `ECONNREFUSED 27017` | MongoDB not running | Run `docker start mongodb` |
| `version missing for spring-ai-starter-model-google-genai` | BOM version too low | Use `spring-ai-bom:1.1.4` or higher |
| IntelliJ: `file outside module source root` | Maven not imported correctly | Right-click `pom.xml` → Add as Maven Project, then reload |