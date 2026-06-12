# Read-Only Security Guardrails

Cloud Resource Sentinel **cannot modify, delete, or create any AWS resources.** This is not a policy or a convention — it is enforced by **four independent layers**, each catching mistakes the others might miss.

| Layer | What it does | Catches |
|-------|--------------|---------|
| **AI Prompt Guardrail** | Instructs the LLM to never output executable commands or scripts | Dangerous suggestions in AI recommendations |
| **ReadOnlyInterceptor** | AWS SDK `ExecutionInterceptor` that blocks all mutating API calls before they hit the network | Runtime protection — even if code tries to delete, it's blocked |
| **ReadOnlyAwsClientFactory** | Centralized factory that builds every AWS client with the interceptor attached | Architectural enforcement — no unprotected clients can exist |
| **ReadOnlyGuardrailTest** | Architecture test that fails the build if any code bypasses the factory | Compile/test-time enforcement — catches developer mistakes |

## How each layer works

### 1. ReadOnlyInterceptor
`config/ReadOnlyInterceptor.java` inspects every AWS API operation name. Only read operations are allowed:

```
Describe*  List*  Get*  Search*  BatchGet*  Scan*  Query*  Head*
+ STS operations like GetCallerIdentity
```

Everything else — `Delete*`, `Create*`, `Modify*`, `Put*`, `Terminate*`, `Stop*`, `Start*`, etc. — throws a `SecurityException` **before the request leaves the process.**

### 2. ReadOnlyAwsClientFactory
`config/ReadOnlyAwsClientFactory.java` is a static factory with `build()` methods that attach the interceptor to any AWS SDK client builder. **All 50 scanners**, `ReservationDetector`, `ResourceAnalyzer`, and `PricingService` use this factory exclusively. There is no other sanctioned way to construct an AWS client.

### 3. ReadOnlyGuardrailTest
`test/.../ReadOnlyGuardrailTest.java` walks every Java source file and asserts that each `XxxClient.builder()` call appears inside a `ReadOnlyAwsClientFactory.build(...)` invocation. **If a developer adds a direct client construction, the build fails.** This is the layer that prevents the guardrail from eroding over time.

### 4. AI Prompt Guardrail
`resources/prompts/analysis-prompt.txt` contains a `SAFETY GUARDRAIL` section instructing the AI to phrase all recommendations as advisory ("Consider terminating", "Recommend downsizing") and never include CLI commands or scripts.

## Required IAM permissions

Because the app only ever calls read operations, the credentials it uses need **read-only access**. AWS's managed `ReadOnlyAccess` or `SecurityAudit` policies are sufficient. The app verifies credentials at startup (`IamPolicyVerifier`) and degrades gracefully to demo/mock-data mode if none are present.

## Auditability

Blocked operations are surfaced via the actuator endpoint `/actuator/blocked-operations`, so you can confirm the interceptor is doing its job.

## Defense in depth — why four layers?

- The **prompt guardrail** stops bad *advice* but can't stop *code*.
- The **interceptor** stops bad *code at runtime* but only if it runs.
- The **factory** ensures the interceptor is *always attached*.
- The **architecture test** ensures the factory is *always used*.

Each layer is independent. A failure or bypass of any single layer is caught by another.
