# API Reference

All endpoints return snake_case JSON (global Jackson `SNAKE_CASE` strategy). Interactive docs are available at **`/swagger-ui.html`** when the app is running.

## Profiles & regions

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/profiles` | List AWS credential profiles |
| GET | `/profiles/check` | Check credential health for a profile |
| GET | `/regions` | List AWS regions (30) |

## AI

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/ai/status` | Check Ollama + Bedrock availability |

## Analysis

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/analyse` | Submit analysis job (max 7 queued) |
| POST | `/analyse/batch` | Batch scan all profiles (with queue depth check) |
| POST | `/analyse/cancel` | Cancel a running job |
| POST | `/analyse/cancel-all` | Cancel all jobs and clear queue |
| GET | `/analyse/job` | Get job status / progress |
| GET | `/analyse/jobs` | List all jobs |
| GET | `/analyse/cached` | Get latest cached report |
| GET | `/analyse/reports` | Get all reports for a profile |
| GET | `/analyse/compare` | Compare two reports (empty-report guard) |

## Export & pricing

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/export/csv` | Export results as CSV (formula-injection safe) |
| GET | `/pricing/status` | Pricing data status |
| POST | `/pricing/refresh` | Refresh pricing from AWS (clears cache) |

## Docs

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/swagger-ui.html` | Swagger UI |
| GET | `/v3/api-docs` | OpenAPI JSON |

## Operational endpoints (Actuator)

| Path | Purpose |
|------|---------|
| `/actuator/health` | Liveness/readiness |
| `/actuator/info` | App name/version |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/blocked-operations` | Audit log of any mutating AWS call the interceptor blocked — proves the read-only guardrail is active |

## Scan submission notes

- **Sequential execution** — one scan runs at a time to avoid overloading the AI model; others queue automatically.
- **Individual queue limit** — max 7 queued individual scans. The batch endpoint checks cumulative queue depth.
- **Duplicate prevention** — atomic check; a profile that already has an active job cannot be resubmitted.
- **Region validation** — max 50 regions per request.
- **Retry logic** — only transient failures (timeouts, throttling) are retried. Credential and access-denied errors are permanent.
- **AI failure resilience** — if AI analysis fails, the report is still saved with scan data (no AI insights); the UI badge shows a "failed" state.
