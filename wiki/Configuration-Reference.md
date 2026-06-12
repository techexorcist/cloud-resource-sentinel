# Configuration Reference

All settings live in `src/main/resources/application.properties` and can be overridden via environment variables or command-line `--property=value` arguments.

## Server

| Property | Default | Notes |
|----------|---------|-------|
| `server.port` | `8000` | Override with `PORT` env var |
| `spring.threads.virtual.enabled` | `true` | Java 21 virtual threads for scanning |
| `server.shutdown` | `graceful` | |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | Graceful shutdown window |

## AI — Ollama (local)

| Property | Default | Notes |
|----------|---------|-------|
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Override with `OLLAMA_HOST` |
| `ai.models.ollama.default` | `llama3.2:latest` | First entry is the default model |
| `ai.models.ollama` | `llama3.2:latest, qwen2.5:3b, phi3:latest, gemma2:latest, mistral:latest, deepseek-r1:latest` | Selectable models |
| `spring.ai.ollama.chat.options.temperature` | `0.3` | |
| `spring.ai.ollama.chat.options.top-p` | `0.9` | |

## AI — AWS Bedrock

| Property | Default | Notes |
|----------|---------|-------|
| `spring.ai.bedrock.aws.region` | `us-east-1` | |
| `ai.models.bedrock.default` | `us.anthropic.claude-sonnet-4-6` | |
| `ai.models.bedrock` | sonnet-4-6, haiku-4-5, opus-4-6, nova-premier/pro/lite | Selectable models |
| `spring.ai.bedrock.converse.chat.options.max-tokens` | `4096` | |

## AI — general

| Property | Default | Notes |
|----------|---------|-------|
| `ai.timeout-seconds` | `1200` | Per-batch AI timeout. **Increase for slow local models.** |
| `spring.http.clients.read-timeout` | `5m` | HTTP read timeout (AI calls can be long) |

## AWS SDK

| Property | Default | Notes |
|----------|---------|-------|
| `aws.default-region` | `us-east-1` | |
| `aws.sdk.max-retries` | `1` | Only transient failures retried |
| `aws.sdk.api-call-timeout-seconds` | `10` | |
| `aws.sdk.api-call-attempt-timeout-seconds` | `5` | |

## Pricing

| Property | Default | Notes |
|----------|---------|-------|
| `pricing.cache.ttl-hours` | `24` | Caffeine cache TTL |

## Scheduled scans

| Property | Default | Notes |
|----------|---------|-------|
| `cloud-sentinel.scheduled-scan.enabled` | `false` | Master switch |
| `cloud-sentinel.scheduled-scan.cron` | `0 0 6 * * *` | Daily at 06:00 |
| `cloud-sentinel.scheduled-scan.category` | `FULL` | `COST` · `SECURITY` · `FULL` |
| `cloud-sentinel.scheduled-scan.ai-enabled` | `false` | |
| `cloud-sentinel.scheduled-scan.ai-provider` | `bedrock` | `ollama` · `bedrock` |
| `cloud-sentinel.scheduled-scan.regions` | *(empty)* | Comma-separated; empty = defaults |

## Audit

| Property | Default | Notes |
|----------|---------|-------|
| `cloud-sentinel.audit.retention-days` | `90` | |

## Actuator / monitoring

| Property | Default | Notes |
|----------|---------|-------|
| `management.endpoints.web.exposure.include` | `health, info, metrics, blocked-operations` | `/actuator/blocked-operations` proves the read-only interceptor is active |

## API docs

| Property | Default |
|----------|---------|
| `springdoc.swagger-ui.path` | `/swagger-ui.html` |
| `springdoc.api-docs.path` | `/v3/api-docs` |

## Common overrides

```bash
# Use a different local model (no download if you already have it)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai.models.ollama.default=qwen2.5:3b"

# Point at a remote Ollama
OLLAMA_HOST=http://my-ollama-host:11434 ./mvnw spring-boot:run

# Run on a different port
PORT=9000 ./mvnw spring-boot:run

# Give slow local models more time
./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai.timeout-seconds=2400"
```
