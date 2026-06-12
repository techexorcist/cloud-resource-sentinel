# Pricing Architecture

All 50 scanners route through `PricingService` for cost calculation. **No scanner contains hardcoded rates.**

```
Scanner → PricingService.getXxxPrice(type, region)
              │
              ├── Try AWS Pricing API
              │     (EC2, RDS, EBS, ELB, ElastiCache, NAT GW, S3,
              │      DynamoDB, Redshift, Aurora)
              │     └── @Cacheable("pricing") — 24hr Caffeine TTL, 1000 max entries
              │
              └── Fallback → FallbackPricing
                      │
                      ├── RegionalPricingData.getInstanceRate(service, region, type)  ← JSON lookup
                      ├── RegionalPricingData.getServiceRate(region, rateKey)          ← JSON lookup
                      └── sizeMultiplier(instanceType) × baseLargeHourly              ← estimation
```

## regional-pricing.json

Located at `resources/pricing/regional-pricing.json`. Covers **30 AWS regions** across these categories:

**Instance-level hourly rates** (per-region, per-instance-type):
`ec2`, `rds`, `elasticache`, `redshift`, `opensearch`, `sagemaker`

**Service-level rates** (30+ rates):
NAT Gateway, EKS, ELB, S3, DynamoDB (provisioned + on-demand), Lambda, SQS, SNS, KMS, CloudWatch, Secrets Manager, EBS snapshots, RDS snapshots, EFS, ECR, Route 53, WAF, Grafana, Glue, Step Functions, API Gateway, Transfer Family, Kinesis.

The data is loaded into `RegionalPricingData`, whose `root` field is **volatile** for safe hot-reload across threads.

## Caching

- `@Cacheable("pricing")` with a **24-hour Caffeine TTL** and 1000 max entries.
- TTL is configurable via `pricing.cache.ttl-hours` (default 24).
- The cache is **automatically cleared** when hot-reloading new pricing data via `POST /pricing/refresh`.

## Why this design

- **Resilience** — if the AWS Pricing API is unreachable or doesn't cover a service, the fallback JSON keeps estimates flowing.
- **Single source of truth** — adjusting a regional rate is one JSON edit, no code change, hot-reloadable.
- **Determinism** — combined with server-side narratives, cost figures are reproducible and not model-dependent.

## Notable per-scanner cost logic

| Scanner | Logic |
|---------|-------|
| **EC2** | $0 for stopped/terminated/shutting-down instances (EBS charged separately) |
| **RDS** | Filters out Aurora cluster members (AuroraScanner handles them) — prevents double-counting |
| **Aurora** | Prices per-member with 1.2× RDS markup in fallback; NPE-safe member lookup |
| **ElastiCache** | Multiplies by `numCacheNodes()` for multi-node clusters |
| **DynamoDB** | Provisioned uses RCU/WCU hourly rates; on-demand estimates from CloudWatch consumed capacity |
| **Elastic IP** | Charges $3.65/mo for **all** public IPv4 (Feb 2024 AWS pricing change) |
| **S3** | Queries 8 storage classes via CloudWatch; skips empty buckets |
| **EBS Snapshots** | Uses paginator for >1000 snapshots |
| **OpenSearch** | Strips `.search` suffix from instance type before lookup |
| **Lightsail** | Bundle-based pricing (nano → 2xlarge) |
| **FSx** | Type-based rates (Windows, Lustre, OpenZFS, ONTAP) |
