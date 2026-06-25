# Cloud Resource Sentinel

[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

AI-powered AWS account watchdog — cost optimization, security posture, and governance findings. Scans AWS accounts across 30 regions to identify idle resources, security gaps, and governance issues, with per-region pricing and AI-powered recommendations.

**50 resource scanners** | **30 AWS regions** | **53 AWS SDK modules** | **Per-region pricing** | **AI-powered insights**

## What It Does

- Scans your AWS accounts for idle resources, security gaps, and governance issues
- Classifies findings as **Cost**, **Security**, or **Governance** with severity levels (INFO → CRITICAL)
- Estimates monthly cost for each resource using real AWS pricing data (per-region)
- Identifies potential savings from idle resources
- Detects security issues: missing MFA, disabled key rotation, expired certificates, exposed credentials
- Detects governance issues: stale stacks, missing retention policies, misconfigured resources
- Correlates resources across services (EBS on stopped EC2, EIPs on idle instances, etc.)
- Provides AI-powered analysis with context-aware prompts (cost vs. security/governance)
- Compares scan reports over time to track trends

## Quick Start

Choose one of the two setup options below.

### Option A: Docker (recommended)

```bash
git clone https://github.com/techexorcist/cloud-resource-sentinel.git
cd cloud-resource-sentinel

# Start both services (Ollama AI + backend)
docker-compose up -d

# Pull a small AI model into Ollama (one-time, ~2 GB)
docker exec sentinel-ollama ollama pull llama3.2

# Open
open http://localhost:8000
```

**Prerequisites:** Docker + Docker Compose, AWS credentials (`~/.aws/config`)

The Docker setup handles everything: Ollama runs in a container with health checks, the backend waits for it to be ready, and AWS credentials are mounted read-only. Reports persist in a named volume.

### Option B: Run locally (without Docker)

#### 1. Install Java 21+

```bash
# macOS (Homebrew)
brew install openjdk@21

# Ubuntu/Debian
sudo apt install openjdk-21-jdk

# Verify
java -version   # Should show 21+
```

#### 2. Install and start Ollama

```bash
# macOS
brew install ollama

# Linux
curl -fsSL https://ollama.com/install.sh | sh

# Start the Ollama server
ollama serve &

# Pull a model (choose one):
ollama pull llama3.2         # 3B params, ~2 GB — fast, good for quick scans
ollama pull qwen2.5:3b       # 3B params, ~2 GB — alternative
ollama pull mistral           # 7B params, ~4 GB — better reasoning, slower

# Verify it's running
curl http://localhost:11434/api/tags
```

> **Which model to pick?** `llama3.2` is the default and works well for most scans. Cost narratives, risk overviews, and executive summary stats are computed server-side, so even small models produce accurate dashboards. The AI only provides interpretive judgment (prioritisation, reasoning, architecture insights). For higher-quality AI narratives, use AWS Bedrock (Claude) via the UI — no local model needed.

#### 3. Configure AWS credentials

```bash
# Option 1: AWS SSO (recommended for organisations)
aws configure sso
aws sso login --profile <your-profile>

# Option 2: Static credentials
aws configure

# Verify
aws sts get-caller-identity
```

#### 4. Build and run

```bash
git clone https://github.com/techexorcist/cloud-resource-sentinel.git
cd cloud-resource-sentinel

# Build (skip tests for faster startup)
./mvnw clean package -DskipTests

# Run
OLLAMA_HOST=http://localhost:11434 ./mvnw spring-boot:run

# Open
open http://localhost:8000
```

#### 5. Run without AI (optional)

The tool works without any AI provider. Scans, pricing, correlations, and all dashboard features work. AI insights (prioritised actions, architecture insights, executive narrative) are simply omitted.

```bash
# Just run — no OLLAMA_HOST needed
./mvnw spring-boot:run
```

### Option C: Demo mode (no AWS credentials needed)

Want to explore the dashboard without connecting to a real AWS account? Two pre-baked demo reports (with and without AI insights) are available — no AWS credentials or AI model required.

**From the UI:** Start the app normally, go to **Settings**, and click **Reload Demo Reports**. The demo profiles appear immediately in the dashboard.

**Auto-load on startup:** Use the `mock-data` Spring profile to load demo reports automatically:

```bash
# Docker
docker-compose up -d --build
# (mock-data profile can be set via SPRING_PROFILES_ACTIVE=mock-data in docker-compose.yml)

# Local
./mvnw spring-boot:run -Dspring-boot.run.profiles=mock-data
```

The demo data includes ~50 resources across 5 regions covering all finding types (Cost, Security, Governance) with severity levels, recommendations, cost estimates, and a full AI analysis including action items, right-sizing suggestions, and a phased cleanup plan.

### Prerequisites summary

| Requirement | Docker setup | Local setup |
|-------------|-------------|-------------|
| **Java 21+** | Not needed (built in container) | Required |
| **Ollama** | Included in docker-compose | Install separately (see above) |
| **AWS credentials** | `~/.aws/config` mounted read-only | `~/.aws/config` on your machine |
| **Docker** | Required | Not needed |

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| **Backend** | Java 21, Spring Boot 4.0, Spring AI 2.0, AWS SDK v2, Virtual Threads |
| **Frontend** | Thymeleaf, Bootstrap 5.3, Chart.js, Vanilla JS |
| **AI** | Ollama (local: llama3.2, qwen2.5, mistral) + AWS Bedrock (Claude Sonnet 4.6, Haiku 4.5) |
| **Caching** | Caffeine (24hr TTL for pricing, auto-cleared on refresh) |
| **Data** | Jackson (snake_case JSON), Apache Commons CSV (formula-injection safe export) |
| **API Docs** | Springdoc OpenAPI 2.8 (Swagger UI at `/swagger-ui.html`) |
| **Infrastructure** | Docker Compose, health checks, memory limits, graceful shutdown |

### Java 21 Features

Virtual Threads (JEP 444), Records (JEP 395), SequencedCollections (JEP 431), Switch Expressions (JEP 361), Text Blocks (JEP 378), `var` type inference (JEP 286).

## Resource Coverage

### Cost & Idle (39 scanners)

API Gateway, Athena, Aurora, CloudFront, CodePipeline, DMS, DocumentDB, DynamoDB (provisioned + on-demand), EBS, EBS Snapshots, EC2, ECR, ECS, EFS, EKS, Elastic Beanstalk, Elastic IP, ElastiCache, ELB (ALB/NLB), FSx, Glue, Kinesis, Lambda, Lightsail, Managed Grafana, MemoryDB, NAT Gateway, Neptune, OpenSearch, RDS, RDS Snapshots, Redshift, Route 53, S3, SageMaker, SNS, SQS, Step Functions, Transfer Family

### Security & Governance (11 scanners)

ACM, CloudFormation, CloudTrail, CloudWatch (Alarms + Log Groups), IAM Users/Roles, KMS, Secrets Manager, Shield Advanced, SSM Parameter Store, VPC, WAF

## Security: Read-Only by Design

Cloud Resource Sentinel **cannot modify, delete, or create any AWS resources**. This is enforced by four independent layers:

| Layer | Protection |
|-------|-----------|
| **AI Prompt Guardrail** | LLM instructed to provide advisory recommendations only, never CLI commands |
| **ReadOnlyInterceptor** | AWS SDK interceptor blocks all mutating API calls before they reach the network |
| **ReadOnlyAwsClientFactory** | Every AWS client built through a centralized factory with the interceptor attached |
| **ReadOnlyGuardrailTest** | Architecture test fails the build if any code bypasses the factory |

## Pricing Architecture

All 50 scanners route through `PricingService` for cost calculation. No scanner contains hardcoded rates.

```
Scanner -> PricingService (tries AWS Pricing API, @Cacheable 24hr)
               |
               +-> FallbackPricing -> RegionalPricingData (JSON file)
                                          |
                                          +-> ~/.cloud-sentinel/regional-pricing.json (persisted)
                                          +-> classpath:pricing/regional-pricing.json (bundled)
```

**Pricing data includes:**
- Instance-level rates for EC2, RDS, ElastiCache, Redshift, OpenSearch, SageMaker (30 regions)
- 38 service-level rates (NAT GW, S3, EBS, Lambda, SQS, SNS, KMS, DynamoDB on-demand, Glue, Step Functions, API Gateway, WAF, and more)
- Refreshable from AWS Pricing API via UI button (persists across restarts)

## API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/analyse` | Submit analysis job |
| `POST` | `/analyse/batch` | Batch scan all profiles |
| `GET` | `/analyse/jobs` | List all jobs with status |
| `GET` | `/analyse/compare` | Compare two scan reports |
| `POST` | `/export/csv` | Export results as CSV |
| `POST` | `/pricing/refresh` | Refresh pricing from AWS |
| `GET` | `/swagger-ui.html` | Full API documentation |

## Architecture

```
                         +---------------------+
                         |   Browser (UI)      |
                         |  Thymeleaf + BS5    |
                         +--------+------------+
                                  |
                         +--------v------------+
                         |  Spring Boot 4.0    |
                         |  REST Controllers   |
                         +--------+------------+
                                  |
              +-------------------+-------------------+
              |                   |                   |
    +---------v------+  +---------v------+  +---------v------+
    | ResourceAnalyzer|  | PricingService |  | AiAnalysisService|
    | (orchestrator) |  | AWS Pricing API|  | Spring AI       |
    +--------+-------+  | + Caffeine     |  +--------+-------+
             |          | + JSON fallback|           |
    +--------v-------+  +---------------+   +-------+--------+
    | 50 Scanners    |                      |                |
    | (virtual       |               +------v---+  +--------v---+
    |  threads)      |               | Ollama   |  | Bedrock    |
    +--------+-------+               | (local)  |  | (Claude)   |
             |                       +----------+  +------------+
    +--------v-------+
    | AWS SDK v2     |
    | (read-only     |
    |  interceptor)  |
    +----------------+
```

## How Scanning Works

1. **Submit** - Validates request, resolves AWS credentials (SSO/static/default)
2. **Scan** - Spawns ~1500 virtual threads (50 scanners x 30 regions) querying AWS APIs and CloudWatch metrics
3. **Price** - Looks up per-region costs via AWS Pricing API with Caffeine cache fallback
4. **Detect** - Checks Reserved Instances and Savings Plans coverage
5. **Correlate** - 30+ rules cross-reference resources (EBS on stopped EC2, orphaned snapshots, idle Lambda + log groups, etc.)
6. **Analyze** - AI generates per-resource reasoning, risk assessment, right-sizing suggestions, and architecture insights. Cost/risk narratives and executive summary statistics are computed server-side for accuracy; the AI adds interpretive judgment only
7. **Save** - Atomic write to disk, keeps up to 3 reports per account, computes diff against previous scan

## Build & Test

```bash
# Build
./mvnw clean package

# Test (402 tests)
./mvnw test

# Run
OLLAMA_HOST=http://localhost:11434 ./mvnw spring-boot:run
```

## Docker

```bash
docker-compose up -d

# First time: pull a model into the Ollama container
docker exec sentinel-ollama ollama pull llama3.2
```

Configuration:

| Setting | Backend | Ollama |
|---------|---------|--------|
| **Memory limit** | 1 GB | 4 GB |
| **Memory reservation** | 768 MB | 1 GB |
| **Health check** | `/actuator/health` every 30s | `/api/tags` every 30s |
| **Security** | read-only filesystem, no capabilities, non-root user | default |
| **Networking** | internal (Ollama) + egress (AWS APIs) | internal only |
| **Volumes** | `~/.aws` (read-only), `sentinel-data` (reports) | `ollama-data` (models) |

JVM flags: `-Xms256m -Xmx512m -XX:+UseG1GC`. Graceful shutdown: 30s. Ollama must be healthy before backend starts.

## Project Structure

```
src/main/java/com/cloudsentinel/
  config/          # Spring config + 4-layer AWS read-only guardrails
  controller/      # REST + view controllers
  dto/             # Request/response DTOs (records + POJOs)
  exception/       # Global error handler
  service/
    scanner/       # ResourceScanner interface + 50 implementations
    pricing/       # PricingService -> FallbackPricing -> RegionalPricingData
    ...            # Analyzer, AI, recommendations, correlation, reports, audit
  util/            # AI response parser
```

## License

MIT License. See [LICENSE](LICENSE) for details.

---

Built with [Claude](https://claude.ai)
