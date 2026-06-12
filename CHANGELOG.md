# Changelog

All notable changes to Cloud Resource Sentinel are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.1.0] - 2026-05-26

### Fixed
- AI cost_narrative and risk_overview fields now computed server-side — eliminates "$X placeholder" bug where small Ollama models echoed prompt templates instead of producing dollar amounts
- AI executive_summary split into server-side stats preamble + AI narrative judgment — prevents hallucinated resource counts and severity breakdowns from small models
- Batched analysis no longer concatenates per-batch narratives — recomputes all narrative fields from the full resource list after merge
- Zero-cost resources (IAM, KMS, ACM, etc.) excluded from "Top savings opportunities" in cost summaries
- Raw JSON contamination in executive_summary detected and rejected — falls back to server-side preamble

### Added
- `PromptBuilder.buildCostSummary()` — pre-computed spend by service type, top-5, idle vs active breakdown
- `PromptBuilder.buildCostNarrative()` — server-side cost narrative paragraph with real dollar amounts
- `PromptBuilder.buildRiskOverview()` — server-side severity counts and actionable resource summary
- `PromptBuilder.buildExecutivePreamble()` — factual stats sentence for executive summary
- `AiAnalysisService.buildExecutiveSummary()` — combines server preamble with sanitized AI judgment
- `AiOutputValidator.validateNoPlaceholders()` — detects "$X/$Y/$Z" placeholder leaks in narrative fields
- `AiOutputValidator.validateNoJsonContamination()` — detects raw JSON structures in narrative fields
- PRE-COMPUTED COST ANALYSIS section in both AI prompt templates
- 6 new tests (cost summary, placeholder detection, JSON contamination)

### Changed
- `cost_narrative` and `risk_overview` removed from AI response schema — computed server-side, never from AI
- `executive_summary` prompt schema changed from "4-5 sentences with stats" to "2-3 sentences of interpretive judgment only"
- Anti-instructions ("Do NOT write $X") removed from prompts — small models echo these as templates
- `mergeInsights()` recomputes narrative fields from full resource list instead of concatenating per-batch
- Fallback path uses server-side narratives instead of raw AI text dump
- Prompt version: 2.1.0 (templates updated, schema fields removed)
- Test count: 396 → 402

## [1.0.0] - 2026-05-23

### Added
- 50 AWS resource scanners (39 cost + 7 security + 4 governance)
- FindingType classification (COST, SECURITY, GOVERNANCE) with per-scanner declaration
- Severity levels (INFO, LOW, MEDIUM, HIGH, CRITICAL) with automatic classification
- Dual AI prompt templates (cost-focused + security/governance-focused) with automatic dispatch
- AI prompt injection defenses (resource list delimiters, post-response command sanitizer)
- Dashboard with Cost / Security / Governance tabs filtering charts, breakdown, and correlations
- 39 cross-resource correlation rules across 7 categories
- Model capability filtering (small Ollama models hidden for full scans)
- Per-region pricing for 30 AWS regions via AWS Pricing API with 24hr Caffeine cache
- Severity-sortable resource table with finding type badges
- Compare page with security/governance finding deltas
- Audit trail with finding-type breakdown and scan category columns
- CSV export with finding_type, severity, and recommendation_detail columns
- 4-layer read-only security guardrail (SDK interceptor, client factory, architecture test, AI prompt)
- Spring Actuator for health/metrics/info endpoints
- Rate limiting on scan submission endpoints
- CSP security headers
- Request logging for non-actuator paths
- Prompt-size-based batching guard preventing Ollama context window overflow
- Audit trail 90-day retention policy
- IAM policy covering all 49 AWS service prefixes (53 SDK modules)
- Docker Compose with health checks, memory limits, and Ollama integration
- 396 tests covering guardrails, recommendations, correlations, finding types, severity, DTOs, API contracts (now 402 with v1.1.0 additions)

### Architecture
- Java 21 with virtual threads for ~1500 concurrent scanner tasks
- Spring Boot 4.0.6 + Spring AI 2.0.0-M4
- All 50 scanners route through RecommendationEngine (43 methods, 16 named constants)
- All AWS clients built via ReadOnlyAwsClientFactory with ReadOnlyInterceptor
- PromptBuilder extracted from AiAnalysisService for testable prompt construction
- AnalyseController split into AnalyseController + ReportController + AuditController
- AiResponseParser handles JSON extraction and command sanitization
