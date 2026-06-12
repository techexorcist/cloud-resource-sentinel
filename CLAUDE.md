# Cloud Resource Sentinel

AI-powered AWS account watchdog — cost optimization, security posture, and governance findings.

## Project Overview

Scans cloud accounts (currently AWS, designed for multi-cloud expansion) to identify idle resources, security gaps, and governance issues. Classifies findings by type (Cost, Security, Governance) and severity (INFO → CRITICAL), estimates costs with per-region pricing, and provides AI-powered recommendations with context-aware prompts.

## Architecture

- **Backend**: Java 21 / Spring Boot 4.0.6 / Spring AI 2.0
- **Frontend**: Server-rendered Thymeleaf + Bootstrap 5
- **AI Providers**: Ollama (local, open-source) + AWS Bedrock (Claude)
- **Infrastructure**: Docker Compose (2 services: ollama, backend)

## Project Structure

```
src/main/java/com/cloudsentinel/
├── config/          # CORS, Jackson (snake_case), Cache (Caffeine), AI config, OpenAPI
│                    # ReadOnlyAwsClientFactory + ReadOnlyInterceptor (4-layer security)
├── controller/      # REST + view controllers (11 controllers)
├── dto/             # Request/response DTOs (snake_case JSON via Jackson)
│                    # Records: AnalysisRequest, AiInsightDto, AiFilteringDto, AiUsageDto
│                    # POJOs: ResourceDto (with negative-cost guard), AnalysisResponse, ScanReportDto
├── service/
│   ├── scanner/     # ResourceScanner interface + 50 implementations
│   ├── pricing/     # AWS Pricing API with 24hr Caffeine cache + regional fallback JSON
│   │                # PricingService → FallbackPricing → RegionalPricingData (volatile, hot-reloadable)
│   └── ...          # Analyzer orchestrator, AI service, recommendations, reports
├── util/            # AI response parser, output validator (placeholder + JSON contamination detection)
└── exception/       # Global error handler (logs full trace, returns generic message)

src/main/resources/
├── templates/pages/ # Thymeleaf templates (dashboard, analyse, resources, stats, etc.)
├── static/          # CSS, JS (sentinel.js — no HTML tooltips, XSS-safe)
├── pricing/         # regional-pricing.json (per-region rates for 30 AWS regions, 5 service categories)
│                    # Includes: ec2, rds, elasticache, redshift, opensearch, sagemaker, services
└── application.properties  # Includes graceful shutdown config
```

## Key Design Decisions

- **snake_case JSON**: Global Jackson SNAKE_CASE strategy for API compatibility
- **Virtual threads**: Java 21 virtual threads for parallel region + resource type scanning (~1500 tasks per scan)
- **Spring AI ChatModel**: Unified interface for Ollama and Bedrock — swap by config, zero code change
- **Server-side narratives**: `cost_narrative`, `risk_overview`, and executive summary stats are computed in Java by `PromptBuilder`, never by the AI. The AI only provides interpretive judgment. This makes dashboard numbers deterministic, model-agnostic, and immune to hallucination.
- **Pricing architecture**: All pricing routes through `PricingService` → `FallbackPricing` → `RegionalPricingData` (JSON). No scanner hardcodes rates. Regional overrides are a single JSON edit.
- **Scanner categories**: Cost & Idle, Security & Governance, Full Scan
- **Report caching**: Up to 3 reports per account with diff comparison. Atomic writes (temp file + move).
- **Read-only by design**: 4-layer guardrail system (see below)
- **Prefix-based actionability**: `isActionable()` uses `startsWith()` matching (not substring) to avoid false positives like "Review - Delete Failed". Cost prefixes: `Idle`, `Consider Terminating`, `Delete`, `Release`, `Unused`, `Empty`, `Inactive`, `Stopped`. Security/governance prefixes: `Rotate`, `Enable`, `Restrict`, `Expired`, `Exposed`, `Missing`, `Stale`, `Misconfigured`.

## Read-Only Security Guardrails

Cloud Resource Sentinel **cannot modify, delete, or create any AWS resources**. This is enforced by four independent layers:

| Layer | What it does | Catches |
|-------|-------------|---------|
| **AI Prompt Guardrail** | Instructs the LLM to never output executable commands or scripts | Prevents dangerous suggestions in AI recommendations |
| **ReadOnlyInterceptor** | AWS SDK `ExecutionInterceptor` that blocks all mutating API calls before they hit the network | Runtime protection — even if code tries to delete, it's blocked |
| **ReadOnlyAwsClientFactory** | Centralized factory that builds every AWS client with the interceptor attached | Architectural enforcement — no unprotected clients can exist |
| **ReadOnlyGuardrailTest** | Architecture test that scans the codebase and fails the build if any code bypasses the factory | Compile-time enforcement — catches developer mistakes |

### How it works

1. **ReadOnlyInterceptor** (`config/ReadOnlyInterceptor.java`) inspects every AWS API operation name. Only read operations are allowed: `Describe*`, `List*`, `Get*`, `Search*`, `BatchGet*`, `Scan*`, `Query*`, `Head*`, plus STS operations like `GetCallerIdentity`. Everything else (`Delete*`, `Create*`, `Modify*`, `Put*`, `Terminate*`, `Stop*`, `Start*`, etc.) throws a `SecurityException`.

2. **ReadOnlyAwsClientFactory** (`config/ReadOnlyAwsClientFactory.java`) is a static factory with `build()` methods that attach the interceptor to any AWS SDK client builder. All 50 scanners, ReservationDetector, ResourceAnalyzer, and PricingService use this factory exclusively.

3. **ReadOnlyGuardrailTest** (`test/.../ReadOnlyGuardrailTest.java`) walks all Java source files and asserts that every `XxxClient.builder()` call appears inside a `ReadOnlyAwsClientFactory.build(...)` invocation. If a developer adds a direct client construction, the build fails.

4. **AI Prompt Guardrail** (`resources/prompts/analysis-prompt.txt`) contains a `SAFETY GUARDRAIL` section that instructs the AI to phrase all recommendations as advisory ("Consider terminating", "Recommend downsizing") and never include CLI commands or scripts.

## Pricing Architecture

All 50 scanners route through `PricingService` for cost calculation. No scanner contains hardcoded rates.

```
Scanner → PricingService.getXxxPrice(type, region)
              │
              ├── Try AWS Pricing API (for EC2, RDS, EBS, ELB, ElastiCache, NAT GW, S3, DynamoDB, Redshift, Aurora)
              │   └── @Cacheable("pricing") — 24hr Caffeine TTL, 1000 max entries
              │
              └── Fallback → FallbackPricing
                      │
                      ├── RegionalPricingData.getInstanceRate(service, region, type)  ← JSON lookup
                      ├── RegionalPricingData.getServiceRate(region, rateKey)          ← JSON lookup
                      └── sizeMultiplier(instanceType) × baseLargeHourly              ← estimation
```

**regional-pricing.json** contains:
- Instance-level hourly rates: `ec2`, `rds`, `elasticache`, `redshift`, `opensearch`, `sagemaker` (per-region, per-instance-type)
- Service-level rates: 30+ rates including NAT Gateway, EKS, ELB, S3, DynamoDB (provisioned + on-demand), Lambda, SQS, SNS, KMS, CloudWatch, Secrets Manager, EBS snapshots, RDS snapshots, EFS, ECR, Route 53, WAF, Grafana, Glue, Step Functions, API Gateway, Transfer Family, Kinesis

**Cache invalidation**: Pricing cache is automatically cleared when hot-reloading new pricing data via `/pricing/refresh`.

## Resource Scanners (50)

**Cost & Idle (39):** API Gateway, Athena, Aurora, CloudFront, CodePipeline, DMS, DocumentDB, DynamoDB (provisioned + on-demand), EBS, EBS Snapshots, EC2, ECR, ECS, EFS, EKS, Elastic Beanstalk, Elastic IP, ElastiCache, ELB (ALB/NLB), FSx, Glue, Kinesis, Lambda, Lightsail, Managed Grafana, MemoryDB, NAT Gateway, Neptune, OpenSearch, RDS, RDS Snapshots, Redshift, Route 53, S3, SageMaker, SNS, SQS, Step Functions, Transfer Family

**Security & Governance (11):** ACM, CloudFormation, CloudTrail, CloudWatch (Alarms + Log Groups), IAM (Users + Roles), KMS, Secrets Manager, Shield Advanced, SSM Parameter Store, VPC, WAF

### Scanner cost logic

- **EC2**: $0 for stopped/terminated/shutting-down instances (EBS volumes charged separately)
- **RDS**: Filters out Aurora cluster members (handled by AuroraScanner) — prevents double-counting
- **Aurora**: Prices per-member with 1.2x RDS markup in fallback; NPE-safe member lookup
- **ElastiCache**: Multiplies by `numCacheNodes()` for multi-node clusters
- **DynamoDB**: Provisioned uses RCU/WCU hourly rates; on-demand estimates from CloudWatch consumed capacity
- **Elastic IP**: Charges $3.65/mo for ALL public IPv4 (Feb 2024 AWS pricing change)
- **S3**: Queries 8 storage classes (Standard, IA, Glacier, etc.) via CloudWatch; skips query for empty buckets
- **EBS Snapshots**: Uses paginator for >1000 snapshots
- **OpenSearch**: Strips `.search` suffix from instance type before pricing lookup
- **Lightsail**: Bundle-based pricing lookup (nano through 2xlarge)
- **FSx**: Type-based rates (Windows, Lustre, OpenZFS, ONTAP)

## Recommendation Engine

Centralized in `RecommendationEngine.java` with 28+ methods. Scanners that have matching engine methods delegate to them (EC2, RDS, Aurora, ElastiCache, DynamoDB, ELB, EIP, NAT GW, S3, Lambda, SQS, SNS, EKS, ECS, Redshift, IAM, KMS, CloudWatch, VPC, Secrets Manager, SSM Parameters, CloudFormation, DMS, ACM, WAF, DocumentDB, Neptune, MemoryDB, Lightsail, CodePipeline, Grafana, Athena).

**Actionability classification** (`ResourceAnalyzer.isActionable()`): Uses `startsWith()` prefix matching. Cost prefixes: `Idle`, `Consider Terminating`, `Delete`, `Release`, `Unused`, `Empty`, `Inactive`, `Stopped`. Security/governance prefixes: `Rotate`, `Enable`, `Restrict`, `Expired`, `Exposed`, `Missing`, `Stale`, `Misconfigured`. This avoids false positives from substrings like "Review - Delete Failed".

**Savings cap**: `potentialSavings` is capped at `totalMonthlyCost` to prevent display of impossible savings.

## Data Integrity

- **Deduplication**: 3-part key `region::resourceType::resourceId` — consistent with `ReportService.resourceKey()`
- **Atomic report writes**: Writes to `.tmp` file, then `Files.move()` with `ATOMIC_MOVE`
- **Synchronized save+prune**: `ReportService.saveReport()` is synchronized to prevent concurrent file corruption
- **Negative cost guard**: `ResourceDto.setMonthlyCostUsd()` clamps to `Math.max(0.0, cost)`
- **Atomic job submission**: `AnalysisJobService.submit()` is synchronized — dedupe check + capacity check + insert are atomic

## Concurrency

- **Virtual threads**: `Executors.newVirtualThreadPerTaskExecutor()` for scanner execution (~1500 tasks per full scan)
- **Progress tracking**: Bounded to phase ranges (scanning: 10-60%, AI: 70-89%, saving: 90%, complete: 100%)
- **Executor shutdown**: Graceful shutdown with 10s `awaitTermination` before `shutdownNow`
- **Volatile pricing data**: `RegionalPricingData.root` is volatile for safe hot-reload across threads
- **Job eviction**: ConcurrentHashMap with volatile field reads — no unnecessary synchronization

## Security

- **CSV export**: Sanitized against formula injection (CWE-1236) — prefixes `=`, `+`, `-`, `@`, `\t`, `\r` with `'`
- **XSS protection**: Tooltips use `data-bs-html: false`; `stopJob` buttons use data attributes + delegated listeners; AI alerts use `esc()` function
- **Error handling**: `GlobalExceptionHandler` logs full stack trace but returns generic "An internal error occurred" to clients
- **Batch rate limiting**: Checks cumulative queue depth before accepting batch submissions
- **Region validation**: Max 50 regions per request

## API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/profiles` | List AWS credential profiles |
| GET | `/profiles/check` | Check credential health for a profile |
| GET | `/regions` | List AWS regions (30) |
| GET | `/ai/status` | Check Ollama + Bedrock availability |
| POST | `/analyse` | Submit analysis job (max 7 queued) |
| POST | `/analyse/batch` | Batch scan all profiles (with queue depth check) |
| POST | `/analyse/cancel` | Cancel a running job |
| POST | `/analyse/cancel-all` | Cancel all jobs and clear queue |
| GET | `/analyse/job` | Get job status/progress |
| GET | `/analyse/jobs` | List all jobs |
| GET | `/analyse/cached` | Get latest cached report |
| GET | `/analyse/reports` | Get all reports for profile |
| GET | `/analyse/compare` | Compare two reports (empty report guard) |
| POST | `/export/csv` | Export results as CSV (formula-injection safe) |
| GET | `/pricing/status` | Pricing data status |
| POST | `/pricing/refresh` | Refresh pricing from AWS (clears cache) |
| GET | `/swagger-ui.html` | Swagger UI |

## Scanning Behavior

- **Sequential execution**: 1 scan runs at a time to avoid overloading the AI model. Others queue automatically.
- **Individual queue limit**: Max 7 queued individual scans. Batch endpoint checks cumulative queue depth.
- **Duplicate prevention**: Atomic check — cannot submit a scan for a profile that already has an active job.
- **Retry logic**: Only transient failures (timeouts, throttling) are retried. Credential errors and access-denied are permanent.
- **AI failure resilience**: If AI analysis fails, the report is still saved with scan data (no AI insights). Badge shows "failed" state in UI.
- **Resource deduplication**: 3-part key (region::type::id) prevents cross-scanner duplicates.
- **Cross-resource correlation**: 30+ rules run after scanning to detect relationships across all 50 resource types.
- **Scanner-based savings**: AI insights do not overwrite scanner-derived `potentialSavings` — keeps idle count and savings consistent.
- **Server-side narratives**: `cost_narrative`, `risk_overview`, and executive summary stats are computed by `PromptBuilder` in Java, not by the AI. The AI's output for these fields is ignored. This prevents placeholder leaks (`$X`/`$Y`), hallucinated statistics, and model-dependent quality. Zero-cost resources are filtered from top-N lists. Batched analysis recomputes narratives from the full resource list after merge.
- **AI output validation**: `AiOutputValidator` detects placeholder leaks (`$X`/`$Y`/`$Z`), JSON contamination in narrative fields, and invalid enum/pattern values. Contaminated executive summaries fall back to the server-side preamble.

## Docker

```yaml
# Health checks on both services
# Memory limits: backend 1G, ollama 4G
# JVM flags: -Xms256m -Xmx512m -XX:+UseG1GC
# Graceful shutdown: 30s timeout
# depends_on: ollama healthy before backend starts
docker-compose up -d
```

## Build & Run

```bash
# Build
./mvnw clean package

# Run with Docker
docker-compose up -d

# Run locally (with local Ollama)
OLLAMA_HOST=http://localhost:11434 ./mvnw spring-boot:run
```

## Testing

```bash
./mvnw test    # 402 tests
```

Tests cover:
- **Security guardrails**: ReadOnlyInterceptor (allowed/blocked operations), architecture test (no AWS client bypasses factory)
- **AI**: Response parsing, usage DTO calculations, fun fact generation, JSON extraction, output validation (placeholder leaks, JSON contamination), server-side narrative computation (cost summary, cost narrative, risk overview, executive preamble)
- **Business logic**: Recommendation thresholds (25+ resource types), resource correlation engine (30+ correlation rules across all 50 resource types), actionability classification (prefix-based, with false-positive regression tests)
- **DTOs**: Validation (AnalysisRequest with region limit), staleness (ScanReportDto), serialization (snake_case API contract), factory methods
- **Services**: Account ID resolution, audit entry persistence, report diffing
- **Pricing**: Fallback values, regional variation, per-service rates

## Code Conventions

- **Java 21 features used**: Virtual threads, records (DTOs, local records), `var`, `List.getFirst()`, switch expressions, text blocks in prompts
- **Naming**: AWS SDK clients use standard abbreviations (`ec2`, `rds`, `cw`, `s3`). All other variables use descriptive names.
- **Scanner pattern**: Each scanner implements `ResourceScanner.scan()` returning `List<ResourceDto>`. All AWS clients built via `ReadOnlyAwsClientFactory`. Error handling per-resource (one failure doesn't abort the scanner).
- **Pricing pattern**: All costs route through `PricingService` → `FallbackPricing` → `RegionalPricingData`. No hardcoded rates in scanners.
