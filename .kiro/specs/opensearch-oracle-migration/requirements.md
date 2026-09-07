# Requirements — Migrate OpenSearch off AWS to a self-hosted Oracle Cloud node

## Overview

Move the catalog search backend from **Amazon OpenSearch Serverless** to a **self-hosted
single-node OpenSearch** running on an Oracle Cloud Ampere A1 VM, at a fraction of the cost,
while preserving keyword, semantic (k-NN vector), and dietary-tag search over the full ~2.23M
RecipeNLG catalog.

This is a **host + transport swap behind the existing `CatalogSearchService` seam**, not a
rewrite. The controller, DTOs (`CatalogRecipeDto`), and frontend depend only on the interface and
do not change. DynamoDB (`recipe-ai-dev-catalog-full`, 2,231,142 recipes + 1024-dim embeddings)
remains the system of record; the OpenSearch index is derived and rebuilt from it with no
re-embedding.

## Background / why

AWS OpenSearch Serverless kept roughly 6.5 OCU warm for the 2.2M-doc vector index even when idle,
forecasting ~$240/mo against a ~$15 budget. The serverless collection was **deleted** to stop the
cost. The full-precision embeddings survive in DynamoDB, so the index can be rebuilt on any host.
The chosen replacement is a self-hosted OpenSearch node on Oracle Cloud (unlimited "Always Free"
plus cheap Pay-As-You-Go compute).

## Glossary

- **Self-hosted OpenSearch** — an OpenSearch container we run and administer, as opposed to the
  managed AWS Serverless service. It supports the full OpenSearch API (custom `_id`/upsert,
  scroll, `_delete_by_query`, `on_disk` k-NN), unlike the serverless subset.
- **OCI** — Oracle Cloud Infrastructure.
- **Ampere A1** — Oracle's ARM compute shape; `VM.Standard.A1.Flex` is the flexible VM form.
- **Basic auth** — HTTP username/password over TLS, the auth model self-hosted OpenSearch uses
  (vs. AWS SigV4 request signing).

## Requirements

### Requirement 1 — Pluggable client transport (SigV4 or basic auth)

**User story:** As the backend, I want to connect to either Amazon OpenSearch (SigV4) or a
self-hosted node (basic auth) chosen by config, so the same code serves both without a rewrite.

#### Acceptance criteria
1. WHEN `opensearch.auth=sigv4` THEN the client SHALL sign requests with AWS SigV4 (existing
   `AwsSdk2Transport` path), unchanged for existing AWS deployments.
2. WHEN `opensearch.auth=basic` THEN the client SHALL authenticate with HTTP basic auth over
   HTTPS using `opensearch.username` / `opensearch.password`.
3. WHEN `opensearch.auth=basic` AND `opensearch.tls-verify=false` THEN the client SHALL accept a
   self-signed server certificate (skip chain + hostname verification).
4. WHEN `opensearch.auth=basic` AND username or password is blank THEN bean creation SHALL fail
   fast with a clear message.
5. WHEN `catalog.search.backend != opensearch` THEN NO OpenSearch client bean SHALL be created
   (the default in-app deployment pulls in neither transport).

### Requirement 2 — Vector quantization that fits the target host

**User story:** As the operator, I want to pick a vector-memory strategy that fits the chosen VM
size, so the 2.23M index does not OOM.

#### Acceptance criteria
1. WHEN `opensearch.knn.quantization=fp16` THEN the index mapping SHALL use the Faiss scalar
   (fp16) encoder (in-memory, ~half the vector memory; for a 24 GB host).
2. WHEN `opensearch.knn.quantization=byte` THEN the index mapping SHALL use disk-based mode
   (`mode: on_disk`, `compression_level: 16x`, `data_type: float`) so the index fits a 12 GB
   host with NO client-side vector conversion.
3. WHEN `opensearch.knn.quantization=none` THEN the mapping SHALL store full-precision floats.
4. WHEN quantization is any other value THEN provisioning SHALL fail fast.
5. The persisted `List<Double>` embeddings and query vectors SHALL NOT require lossy conversion
   in application code for any supported mode.

### Requirement 3 — Idempotent upsert on the self-hosted node

**User story:** As the operator, I want reindex/backfill to be safely re-runnable on the
self-hosted node, so a partial run can simply be repeated without creating duplicates.

#### Acceptance criteria
1. WHEN `opensearch.auth=basic` THEN the reindex SHALL index each recipe using
   `catalogRecipeId` as the document `_id` (real upsert), NOT auto-generated ids.
2. WHEN a reindex/backfill is re-run against the self-hosted node THEN already-present documents
   SHALL be overwritten, not duplicated.
3. The serverless-only workarounds (auto-generated `_id`, PIT-only reconciliation) SHALL remain
   available for the SigV4/serverless path but SHALL NOT be forced on the self-hosted path.

### Requirement 4 — Terraform-provisioned Oracle node (opt-in, cost-bounded)

**User story:** As the operator, I want the Oracle node defined as opt-in infrastructure as code,
so it is reproducible, reviewable, and provisions nothing by default.

#### Acceptance criteria
1. WHEN `enable_oci_opensearch=false` (default) THEN the module SHALL create NO OCI resources.
2. WHEN enabled THEN the module SHALL provision one `VM.Standard.A1.Flex` VM (configurable OCPU /
   memory; default 2 OCPU / 24 GB), a VCN + public subnet + internet gateway + route table, a
   security list, a boot volume, and cloud-init that installs Docker and runs single-node
   OpenSearch with basic auth over HTTPS.
3. The security list SHALL restrict SSH (22) and the OpenSearch API (9200) to a configurable
   admin CIDR; it SHALL NOT open either port to `0.0.0.0/0`.
4. WHEN enabled AND the admin CIDR or the OpenSearch admin password is unset THEN `terraform
   plan` SHALL fail with a clear precondition message.
5. The OpenSearch admin password SHALL be supplied as a sensitive variable and SHALL NOT be
   committed to version control.
6. The OCI provider credentials SHALL be non-secret OCIDs/fingerprint/region plus a private-key
   **file path**; the private key SHALL NOT be committed.

### Requirement 5 — Rebuild + verify tooling against the new endpoint

**User story:** As the operator, I want one command to rebuild and verify the index on the Oracle
node, so completeness is guaranteed before any cutover.

#### Acceptance criteria
1. There SHALL be a script that targets the Oracle endpoint with basic auth via environment
   variables (endpoint, username, password), reading embeddings from DynamoDB with NO
   re-embedding.
2. The script SHALL recreate the index, bulk-load all recipes, backfill any failures, then verify
   the OpenSearch `_count` equals the DynamoDB item count, exiting non-zero if short.
3. WHEN the load is complete THEN the verify step SHALL report **2,231,142 == 2,231,142**.

### Requirement 6 — Documentation and truthful status

**User story:** As a future reader, I want the docs to reflect the current state, so nothing
claims AWS OpenSearch is "live" when it has been deleted.

#### Acceptance criteria
1. An operator runbook SHALL document account setup, provisioning, loading, serving config, the
   free-tier fallback, and teardown.
2. README/handoff docs SHALL state that the deployed app serves search from the **in-app** backend
   until the Oracle node is reachable, and that AWS OpenSearch Serverless was deleted for cost.

### Requirement 7 — ECS → Oracle reachability decided before cutover (open item)

**User story:** As the operator, I want the live app's network path to the Oracle node resolved
before flipping the backend, so cutover does not break search.

#### Acceptance criteria
1. WHILE the ECS egress path to the node is undecided THE deployed app SHALL remain on
   `catalog_search_backend=inapp`.
2. Because ECS tasks run in public subnets with dynamic public IPs (no NAT gateway), the design
   SHALL record the reachability options (NAT gateway + Elastic IP, reverse proxy, or
   laptop-only) and SHALL NOT assume a stable app egress IP.
3. WHEN reachability is established THEN 9200 access SHALL be extended to the app's stable source
   (e.g. a NAT EIP), NOT opened to the internet.

## Non-goals

- Re-embedding the catalog (embeddings are reused from DynamoDB).
- A multi-node / HA OpenSearch cluster (single node is sufficient for this dev workload).
- Changing the search API, DTOs, ranking behavior, or the frontend.
- Removing the AWS Serverless Terraform module (it stays opt-in but disabled).

## Constraints & decisions (confirmed with the user)

- **Host:** Oracle Cloud **Pay-As-You-Go**, `VM.Standard.A1.Flex`, **2 OCPU / 24 GB**, always on
  (~$13/mo; the extra 12 GB over the free 12 GB grant). Shape is a variable; the free $0 fallback
  is 12 GB + `byte` quantization.
- **Quantization:** `fp16` on 24 GB (validated at 2.2M on AWS). `byte`/`on_disk` is the 12 GB path.
- **Auth:** basic auth over HTTPS with a self-signed cert (`tls-verify=false`), safe because 9200
  is IP-locked by the security list.
- **Network:** SSH (22) + 9200 locked to admin IP `70.95.245.8/32` initially; build/verify from
  the operator laptop; ECS reachability decided before cutover (Requirement 7).
- **Region:** OCI `us-sanjose-1`; AWS stays `us-east-1` (DynamoDB source of truth).
