# FAQ

### Can this tool delete or modify my AWS resources?

**No — by design, and it's enforced four independent ways.** A runtime SDK interceptor blocks every non-read API call, a centralized factory ensures that interceptor is always attached, an architecture test fails the build if any code bypasses the factory, and the AI prompt is constrained to advisory language only. See [[Read-Only Security Guardrails]].

### What AWS permissions does it need?

Read-only. AWS's managed `ReadOnlyAccess` or `SecurityAudit` policy is sufficient. The app verifies credentials at startup and degrades gracefully to demo mode if none are present.

### Do I have to use the cloud / a paid AI service?

No. The default AI provider is **Ollama**, which runs locally and is free and open-source. AWS Bedrock (Claude) is an optional alternative selected by config — no code change to switch.

### Are the cost and savings numbers from the AI?

No. All cost narratives, risk overviews, and summary statistics are computed **in Java** by `PromptBuilder`. The AI only provides interpretive recommendations. This makes the numbers deterministic, model-agnostic, and immune to hallucination. See [[Architecture]].

### How accurate is the pricing?

Pricing first tries the live **AWS Pricing API** (cached 24h), then falls back to a per-region JSON rate table covering 30 regions. Estimates are intended for relative comparison and prioritization, not billing-grade precision. See [[Pricing Architecture]].

### Which resources does it scan?

50 scanners — 39 cost/idle and 11 security/governance — across compute, storage, database, networking, and security services. See [[Resource Scanners]] for the full list.

### Can I add a scanner for a service that isn't covered?

Yes. Implement `ResourceScanner`, build clients via `ReadOnlyAwsClientFactory`, route costs through `PricingService`, and add a regional rate to `regional-pricing.json`. The "Adding a new scanner" section of [[Resource Scanners]] has the checklist.

### Does it support clouds other than AWS?

Currently AWS only, but it's architected for multi-cloud expansion (finding types, severities, and the scanner interface are cloud-agnostic).

### How many accounts/scans can run at once?

One scan runs at a time (to avoid overloading the AI model); up to 7 individual scans queue. A batch endpoint can sweep all profiles, subject to a cumulative queue-depth check.

### Can I schedule scans automatically?

Yes — disabled by default. Enable `cloud-sentinel.scheduled-scan.enabled=true` and set the cron, category, and AI provider. See [[Configuration Reference]].

### How do I prove the read-only guardrail is actually working?

Hit `/actuator/blocked-operations` — it audits any mutating AWS call the interceptor blocked. In normal operation it stays empty because no code attempts mutations.

### It won't start / the AI errors out. Where do I look?

See [[Troubleshooting]] — it covers port conflicts, the `./mvnw` permission issue, Ollama "connection refused", AWS region/credential warnings, and AI timeouts.

### Is my data sent anywhere?

With **Ollama**, AI analysis runs entirely locally — nothing leaves your machine. With **Bedrock**, scan summaries are sent to AWS Bedrock for analysis. AWS resource metadata is read directly from the AWS APIs using your own credentials either way.
