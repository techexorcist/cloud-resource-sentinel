# Cloud Resource Sentinel — Wiki

**AI-powered AWS account watchdog** for cost optimization, security posture, and governance findings.

Cloud Resource Sentinel scans AWS accounts to surface idle resources, security gaps, and governance issues. Every finding is classified by **type** (Cost · Security · Governance) and **severity** (INFO → CRITICAL), costed with per-region pricing, and paired with an AI-generated recommendation. It is **read-only by design** — it can never modify, delete, or create AWS resources.

> For installation and a 5-minute Quick Start, see the [README](https://github.com/techexorcist/cloud-resource-sentinel#readme). This wiki covers the *why* and the *how it works internally*.

## Wiki contents

| Page | What it covers |
|------|----------------|
| [[Architecture]] | System layers, request flow, key design decisions |
| [[Read-Only Security Guardrails]] | The 4-layer system that makes mutation impossible |
| [[Pricing Architecture]] | How costs are calculated, regional rates, caching |
| [[Resource Scanners]] | All 50 scanners and their cost logic |
| [[Configuration Reference]] | Every tunable property and environment variable |
| [[API Reference]] | REST endpoints |
| [[Troubleshooting]] | Common startup and runtime issues |
| [[FAQ]] | Frequently asked questions |

## At a glance

- **Backend:** Java 21 · Spring Boot 4.0.6 · Spring AI 2.0
- **Frontend:** Server-rendered Thymeleaf + Bootstrap 5
- **AI providers:** Ollama (local, open-source) or AWS Bedrock (Claude) — swap by config, zero code change
- **Infrastructure:** Docker Compose (ollama + backend)
- **Coverage:** 50 resource scanners (39 cost/idle, 11 security/governance)
- **Tests:** 402

## Core principles

1. **Read-only, always.** Four independent guardrail layers ensure no AWS resource can ever be mutated.
2. **Deterministic numbers.** All cost narratives, risk overviews, and summary statistics are computed in Java — the AI provides interpretive judgment only, never the figures. This makes dashboard numbers model-agnostic and immune to hallucination.
3. **Centralized pricing.** All 50 scanners route through one pricing path. No scanner hardcodes rates; regional overrides are a single JSON edit.
4. **Model-agnostic AI.** A unified Spring AI `ChatModel` interface means switching between Ollama and Bedrock is a config change.
