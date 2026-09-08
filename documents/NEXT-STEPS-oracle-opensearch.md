# Handoff: move OpenSearch off AWS → self-hosted on Oracle Cloud Free Tier

Short handoff so a new conversation can pick this up cleanly. Full background is in
`documents/opensearch-implementation.md`.

## Why we're here
AWS OpenSearch Serverless kept ~6.5 OCU warm for the 2.2M-doc vector index even when idle
(~$240/mo forecast, vs a ~$15 budget). We deleted the collection to stop the cost and decided to
self-host OpenSearch on a cheaper host.

## Current state (DONE — safe)
- ✅ AWS OpenSearch Serverless collection `recipe-ai-dev-catalog` (id `o2dmi7wacuk8u8y9pbm6`)
  **DELETED**. No OCU billing. Leftover free policies (data/enc/net) remain — harmless.
- ✅ `main` `infrastructure/environments/dev.tfvars` flipped so `terraform apply` will NOT recreate
  it: `enable_opensearch=false`, `catalog_search_backend=inapp`, `enable_batch_embedding=false`.
  `enable_catalog_full=true` KEPT. Committed `ddd99c6`, pushed to `origin/main`.
- ✅ App runs on the **in-app** search backend (default) — nothing broke.
- ✅ DynamoDB `recipe-ai-dev-catalog-full`: 2,231,142 recipes + 1024-dim embeddings, intact
  (~29 GB, ~$7/mo). **This is the source of truth** — the index rebuilds from it with NO
  re-embedding (the reindex reads vectors from DynamoDB).

## Decision
Host: **Oracle Cloud Always-Free ARM** (Ampere A1). NOTE: as of 2026-06-15 the free allowance was
halved to **2 OCPU / 12 GB RAM** (was 4/24). 12 GB is TIGHT for this index at fp16, so we plan to
switch to **byte quantization** to fit. Fallback if 12 GB fails: **Hetzner ~16 GB for ~$16/mo**
(a rebuild on a different host, cheap because embeddings are in DynamoDB). Oracle *paid* 24 GB is
~$50/mo, so the cheap-reliable fallback is Hetzner, not Oracle-paid.

## Plan (the agreed "recommended sequence") — status
1. [DONE] Confirm AWS OpenSearch gone + tfvars won't recreate it.
2. [DONE] **App portability code:**
   - `OpenSearchConfig` now supports `opensearch.auth=sigv4|basic`. `basic` uses the Apache
     HttpClient5 transport (`ApacheHttpClient5TransportBuilder`) with a `BasicCredentialsProvider`
     over HTTPS; the SigV4 (`AwsSdk2Transport`) path is kept for AWS.
   - Added `opensearch.username` / `opensearch.password` / `opensearch.tls-verify` (self-signed
     cert toggle) + env passthroughs in `application.properties`.
   - Index mapping supports `opensearch.knn.quantization=byte`, implemented as disk-based
     (`on_disk`, 16x compression) so `data_type` stays `float` (no client-side vector conversion)
     and the 2.2M index fits a 12 GB box. `none`/`fp16` unchanged for AWS.
   - `CatalogReindexRunner` treats `auth=basic` as a full cluster (not serverless) → uses
     `catalogRecipeId` as `_id` for idempotent upserts on the self-hosted node.
3. [DONE] **OCI Terraform module** `infrastructure/modules/oci-opensearch` (`oracle/oci` provider):
   VM.Standard.A1.Flex (default **2 OCPU / 24 GB**, shape is a var; 12 GB free-tier fallback),
   VCN + public subnet + internet gateway + route table + security list (SSH 22 and OpenSearch
   9200 locked to the admin IP), 100 GB boot volume, and cloud-init that installs Docker + runs
   `opensearchproject/opensearch:2.17.1` single-node (security plugin: HTTPS + basic auth on 9200)
   with an auto heap (~half of RAM, capped 31 GB). `terraform validate` passes.
4. [DONE] **Operator steps** written: `documents/RUNBOOK-oracle-opensearch.md` (account + API key,
   tfvars incl. gitignored secret password, `terraform apply`, reindex, serve config, 12 GB
   free-tier fallback, ECS→Oracle reachability open item, teardown).
5. [DONE (tooling)] **Rebuild + verify:** `scripts/run-oracle-catalog-reindex.sh` points the
   reindex at the Oracle endpoint (basic auth via env), recreates + bulk-loads from DynamoDB (no
   re-embed), backfills, then `verify-count` → expect 2,231,142. **Still to RUN by the operator**
   once the node is applied.
6. [PARTIAL] README/docs de-stale-d (AWS "live" claims corrected to "in-app now; moving to
   self-hosted Oracle"). **Flip `catalog_search_backend=opensearch`** happens only after the
   Oracle node is applied, loaded, verified, AND reachable from ECS (see open item below).

## Decisions locked in (this session)
- **Shape:** 2 OCPU / 24 GB, ~$13/mo on **Pay-As-You-Go** (extra 12 GB over the free 12 GB grant).
  2 OCPU is plenty (workload is memory-bound). 24 GB → `fp16` (validated at 2.2M on AWS). Free
  $0 fallback: 12 GB + `byte` (one-line tfvars change).
- **Auth:** basic auth over HTTPS, self-signed cert, `tls-verify=false` (safe: 9200 IP-locked).
- **NETWORK GOTCHA (open item):** ECS runs in **public subnets with dynamic public IPs and no NAT
  gateway**, so there is NO stable app egress IP to whitelist for 9200. For now SSH + 9200 are
  locked to the admin IP and the reindex/verify run from the operator laptop. Before cutover,
  decide: NAT gateway + EIP (~$32/mo, cleanest) vs a reverse proxy vs keeping laptop-only. Until
  then the deployed app stays on `inapp`.

## Operator credentials captured (non-secret)
- OCI region `us-sanjose-1`; user/tenancy OCIDs + key fingerprint recorded in the RUNBOOK tfvars
  example. Private key stays at `~/.oci/oci_api_key.pem` (never committed). Admin IP `70.95.245.8`.

## Open decisions confirmed with user
- Auth: **basic auth over HTTPS** (simplest; good enough for this project).
- Network: Oracle node reachable from **AWS ECS over the internet**, locked down by OCI security
  list to the app's egress IPs + admin IP (no VPN/peering — overkill here).

## Reusable tooling that already works (endpoint-agnostic)
- `scripts/run-full-catalog-reindex.sh` — recreate index + bulk load 2.23M + auto-backfill + verify.
- `scripts/run-catalog-backfill.sh` — index only missing docs (PIT reconcile or ids file).
- `scripts/probe-pit-support.sh` — 5s PIT check.
- `catalog.reindex.verify-count=true` — compares OpenSearch `_count` to DynamoDB; the real
  completeness gate.
- All of these just need the endpoint + auth pointed at the new host.

## Key facts / gotchas to carry forward
- Self-hosted OpenSearch (unlike aoss) SUPPORTS custom `_id` (real upsert), scroll, and
  `_delete_by_query` — so reindex/backfill get MUCH simpler; the serverless workarounds
  (PIT-only reconcile, no-upsert, terms/scroll 404/500) mostly go away. Consider using
  `catalogRecipeId` as `_id` again for idempotent upserts on the self-hosted node.
- ALWAYS set client timeouts (we learned this the hard way — a stuck socket hung a scan ~50 min).
  The DynamoDB client now has apiCall/attempt timeouts + retries; keep them.
- AWS account 412381751532, us-east-1. DynamoDB full table: `recipe-ai-dev-catalog-full`.
