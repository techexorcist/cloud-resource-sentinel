# Contributing to Cloud Resource Sentinel

## Setup

```bash
# Prerequisites: Java 21, Maven, Docker (for Ollama)
git clone <repo-url>
cd cloud-resource-sentinel
./mvnw clean test          # Run all tests (402)
./mvnw spring-boot:run     # Start locally (port 8000)
```

## Adding a New Scanner

1. Create `src/main/java/com/cloudsentinel/service/scanner/MyServiceScanner.java`
2. Implement `ResourceScanner` interface
3. Use `Ec2Scanner.java` as the style template (4-space indent, try-with-resources, `@Override`)
4. Build AWS clients via `ReadOnlyAwsClientFactory.build()` — never construct directly
5. Add a corresponding method to `RecommendationEngine` and call it from your scanner
6. Override `findingType()` if your scanner is SECURITY or GOVERNANCE (default is COST)
7. Override `category()` to return `SECURITY_GOVERNANCE` if applicable
8. Add class-level and method-level Javadoc
9. Run `./mvnw test` — the `ReadOnlyGuardrailTest` will verify your scanner uses the factory

## Code Style

- 4-space indentation throughout
- Try-with-resources for all AWS SDK clients
- `var` for local type inference where clear
- Named constants for magic numbers (see `RecommendationEngine` constants)
- `@Override` on all interface method implementations
- Class-level + method-level Javadoc on scanners
- Recommendation strings must start with an actionable prefix recognized by `ResourceAnalyzer.isActionable()`

## Testing

- Run `./mvnw test` before submitting changes
- New recommendation methods need tests in `RecommendationEngineTest`
- New correlation rules need tests in `ResourceCorrelationEngineTest`
- New finding types/severity need tests in `FindingTypePipelineTest`
- New API endpoints need tests in `ApiContractTest`

## Architecture Rules

- **Read-only by design**: 4 layers enforce this. Never bypass `ReadOnlyAwsClientFactory`.
- **No hardcoded recommendation strings in scanners**: Route through `RecommendationEngine`.
- **No hardcoded pricing in scanners**: Route through `PricingService`.
- **FindingType must be declared per scanner**: Override `findingType()` on the interface.
- **AI prompts are versioned**: Update `PROMPT_VERSION` in both prompt files when changing them.
