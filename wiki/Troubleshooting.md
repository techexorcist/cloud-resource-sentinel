# Troubleshooting

## `permission denied: ./mvnw`

The Maven wrapper lost its executable bit (common when copied/synced between machines, e.g. via cloud sync or zip). On macOS a Gatekeeper quarantine flag can also block it.

```bash
chmod +x mvnw      # restore executable bit
xattr -c mvnw      # strip macOS quarantine/extended attributes
./mvnw --version   # should print Apache Maven 3.9.9
```

If it still won't run, bypass the file-exec entirely:

```bash
sh ./mvnw spring-boot:run
```

## `Web server failed to start. Port 8000 was already in use.`

A previous instance is still running. Free the port, wait for the OS to release it, then restart:

```bash
lsof -ti tcp:8000 | xargs kill -9
while lsof -nP -iTCP:8000 -sTCP:LISTEN >/dev/null 2>&1; do sleep 0.3; done
./mvnw spring-boot:run
```

If `lsof` finds nothing but the error persists, an orphaned JVM is holding it:

```bash
ps aux | grep -i "[s]pring-boot\|cloud-resource-sentinel" | awk '{print $2}' | xargs kill -9
```

You can also just run on a different port: `PORT=9000 ./mvnw spring-boot:run`.

## `Connect to http://localhost:11434 failed: Connection refused`

The Ollama **server** isn't running (installing the CLI and pulling models is not enough — the daemon must be listening).

```bash
ollama serve                  # run in its own terminal
# or, if installed via Homebrew:
brew services start ollama

# verify before relying on it:
curl -s http://localhost:11434/api/tags   # should return JSON, not "connection refused"
```

The app warms up the default model at startup, so once Ollama is serving, restart the app (or it'll simply work on the next scan).

## `Failed to load region from DefaultAwsRegionProviderChain, using US_EAST_1`

**Harmless for local dev.** The app logs this as *"expected in mock-data mode or local development"* and defaults Bedrock to `us-east-1`. You only need to resolve it for real AWS scans or Bedrock usage:

```bash
export AWS_REGION=us-east-1
```

## `IAM policy verification skipped — no valid AWS credentials available`

Also expected in demo/local mode. To run real scans, refresh credentials:

```bash
# AWS SSO
aws sso login --profile <your-profile>

# or static credentials
export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...
```

The app only needs **read-only** permissions (`ReadOnlyAccess` or `SecurityAudit` managed policy).

## AI analysis times out on a local model

Slow/large models can exceed the per-batch timeout. Increase it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai.timeout-seconds=2400"
```

Or switch to a smaller model you already have:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai.models.ollama.default=qwen2.5:3b"
```

## The default model isn't installed

The project defaults to `llama3.2:latest`. Either pull it, or override to a model you have:

```bash
ollama pull llama3.2:latest                  # option A
# option B: override (see "AI analysis times out" above)
ollama list                                  # see what you already have
```

## Want to run with no AWS account at all?

Use demo / mock-data mode — see the README's "Demo mode" section. No credentials required.
