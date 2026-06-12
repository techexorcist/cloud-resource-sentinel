# Architecture

## Layers

```
┌─────────────────────────────────────────────────────────────┐
│  Thymeleaf + Bootstrap 5  (server-rendered UI, XSS-safe JS)   │
├─────────────────────────────────────────────────────────────┤
│  Controllers (11)  — REST + view controllers                 │
├─────────────────────────────────────────────────────────────┤
│  Services                                                     │
│    AnalysisJobService   — job queue, lifecycle, progress      │
│    ResourceAnalyzer     — orchestrates 50 scanners            │
│    AiAnalysisService    — Ollama / Bedrock via Spring AI      │
│    PromptBuilder        — server-side deterministic narratives│
│    RecommendationEngine — 28+ advisory recommendation methods │
│    ReportService        — caching, diffing, atomic writes     │
│    PricingService       — cost calculation (cached)           │
├─────────────────────────────────────────────────────────────┤
│  ReadOnlyAwsClientFactory + ReadOnlyInterceptor              │
│    (every AWS client is built here, with the interceptor)    │
├─────────────────────────────────────────────────────────────┤
│  AWS SDK v2  →  AWS APIs (Describe* / List* / Get* only)     │
└─────────────────────────────────────────────────────────────┘
```

## Package layout

```
com.cloudsentinel/
├── config/      CORS, Jackson (snake_case), Caffeine cache, AI config, OpenAPI
│                ReadOnlyAwsClientFactory + ReadOnlyInterceptor
├── controller/  REST + view controllers (11)
├── dto/         Request/response DTOs (snake_case JSON), FindingType, Severity
├── service/
│   ├── scanner/ ResourceScanner interface + 50 implementations
│   ├── pricing/ PricingService → FallbackPricing → RegionalPricingData
│   └── ...      Analyzer orchestrator, AI service, recommendations, reports
├── util/        AI response parser, output validator
└── exception/   Global error handler
```

## Request flow — a scan

1. **Submit** — `POST /analyse` lands in `AnalysisJobService.submit()` (synchronized: dedupe + capacity check + insert are atomic). Max 7 queued; one scan runs at a time to avoid overloading the AI model.
2. **Scan** — `ResourceAnalyzer` fans out across regions × resource types using **Java 21 virtual threads** (~1500 tasks per full scan). Each scanner is read-only and isolates per-resource failures.
3. **Deduplicate** — results merged on a 3-part key `region::resourceType::resourceId`.
4. **Correlate** — 30+ cross-resource correlation rules detect relationships across all 50 resource types.
5. **Cost** — every resource is priced through `PricingService`.
6. **Narratives (Java)** — `PromptBuilder` computes `cost_narrative`, `risk_overview`, and executive summary stats **deterministically in Java**.
7. **AI (judgment only)** — `AiAnalysisService` calls Ollama or Bedrock for interpretive recommendations. If AI fails, the report is still saved with scan data.
8. **Save** — `ReportService` writes atomically (temp file + `ATOMIC_MOVE`), keeping up to 3 reports per account with diff comparison.

Progress is bounded to phase ranges: scanning 10–60%, AI 70–89%, saving 90%, complete 100%.

## Key design decisions

| Decision | Rationale |
|----------|-----------|
| **snake_case JSON** | Global Jackson `SNAKE_CASE` strategy for API compatibility |
| **Virtual threads** | Cheap parallelism for ~1500 I/O-bound scan tasks |
| **Spring AI `ChatModel`** | One interface for Ollama and Bedrock; swap by config |
| **Server-side narratives** | Deterministic, model-agnostic numbers; immune to hallucination |
| **Centralized pricing** | No scanner hardcodes rates; regional overrides are one JSON edit |
| **Read-only by design** | 4 independent guardrail layers (see [[Read-Only Security Guardrails]]) |
| **Prefix-based actionability** | `startsWith()` matching avoids false positives like "Review - Delete Failed" |

## Data integrity

- **Deduplication:** 3-part key `region::resourceType::resourceId`, consistent with `ReportService.resourceKey()`.
- **Atomic report writes:** write to `.tmp`, then `Files.move()` with `ATOMIC_MOVE`.
- **Synchronized save+prune:** `ReportService.saveReport()` is synchronized to prevent concurrent file corruption.
- **Negative cost guard:** `ResourceDto.setMonthlyCostUsd()` clamps to `Math.max(0.0, cost)`.
- **Savings cap:** `potentialSavings` is capped at `totalMonthlyCost`.
