# Resource Scanners

Cloud Resource Sentinel ships **50 read-only scanners**. Each implements `ResourceScanner.scan()` returning `List<ResourceDto>`, builds its AWS clients via `ReadOnlyAwsClientFactory`, and isolates errors per-resource (one failure doesn't abort the scanner).

## Cost & Idle (39)

API Gateway · Athena · Aurora · CloudFront · CodePipeline · DMS · DocumentDB · DynamoDB (provisioned + on-demand) · EBS · EBS Snapshots · EC2 · ECR · ECS · EFS · EKS · Elastic Beanstalk · Elastic IP · ElastiCache · ELB (ALB/NLB) · FSx · Glue · Kinesis · Lambda · Lightsail · Managed Grafana · MemoryDB · NAT Gateway · Neptune · OpenSearch · RDS · RDS Snapshots · Redshift · Route 53 · S3 · SageMaker · SNS · SQS · Step Functions · Transfer Family

## Security & Governance (11)

ACM · CloudFormation · CloudTrail · CloudWatch (Alarms + Log Groups) · IAM (Users + Roles) · KMS · Secrets Manager · Shield Advanced · SSM Parameter Store · VPC · WAF

## Scanner categories

Scanners group into three scan modes selectable at submission time:

- **Cost & Idle** — the 39 cost scanners
- **Security & Governance** — the 11 security scanners
- **Full Scan** — all 50

## Findings model

Every finding carries:

- **FindingType** — `COST`, `SECURITY`, or `GOVERNANCE`
- **Severity** — `INFO` → `LOW` → `MEDIUM` → `HIGH` → `CRITICAL`
- **Estimated monthly cost** (via [[Pricing Architecture]])
- **An advisory recommendation** (via the Recommendation Engine)

## Recommendation Engine

Centralized in `RecommendationEngine.java` with 28+ methods. Scanners with a matching engine method delegate to it (EC2, RDS, Aurora, ElastiCache, DynamoDB, ELB, EIP, NAT GW, S3, Lambda, SQS, SNS, EKS, ECS, Redshift, IAM, KMS, CloudWatch, VPC, Secrets Manager, SSM Parameters, CloudFormation, DMS, ACM, WAF, DocumentDB, Neptune, MemoryDB, Lightsail, CodePipeline, Grafana, Athena).

### Actionability classification

`ResourceAnalyzer.isActionable()` uses **`startsWith()` prefix matching** (not substring) to avoid false positives like "Review - Delete Failed":

- **Cost prefixes:** `Idle`, `Consider Terminating`, `Delete`, `Release`, `Unused`, `Empty`, `Inactive`, `Stopped`
- **Security/Governance prefixes:** `Rotate`, `Enable`, `Restrict`, `Expired`, `Exposed`, `Missing`, `Stale`, `Misconfigured`

## Cross-resource correlation

After scanning, 30+ correlation rules run across **all 50 resource types** to detect relationships (e.g. unattached EBS volume tied to a terminated instance, idle NAT gateway in a VPC with no active workloads). These run on the deduplicated result set.

## Adding a new scanner

1. Implement `ResourceScanner` and return `List<ResourceDto>` from `scan()`.
2. Build every AWS client via `ReadOnlyAwsClientFactory.build(...)` — **never** call `XxxClient.builder()` directly, or `ReadOnlyGuardrailTest` will fail the build.
3. Route all cost calculation through `PricingService` — no hardcoded rates.
4. Add an advisory recommendation (delegate to `RecommendationEngine` where a method fits).
5. Add a regional rate entry to `regional-pricing.json` if the service isn't already covered.
