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

## Plan (the agreed "recommended sequence") — remaining work
1. [DONE] Confirm AWS OpenSearch gone + tfvars won't recreate it.
2. [ ] **App portability code:**
   - `OpenSearchConfig`: add a basic-auth (username/password over HTTPS) transport path using the
     standard `RestClient` transport, selected by a new `opensearch.auth=basic|sigv4` switch
     (self-hosted OpenSearch uses basic auth, NOT AWS SigV4). Keep the SigV4 path for AWS.
   - Add `opensearch.username` / `opensearch.password` (+ TLS verify toggle for a self-signed cert).
   - Index mapping: switch quantization to **`byte`** so the index fits in 12 GB.
3. [ ] **OCI Terraform module** (`oracle/oci` provider): VM.Standard.A1.Flex (2 OCPU/12 GB),
   VCN + subnet + internet gateway + security list (9200 to the app's egress IPs + SSH from admin
   IP), a block volume, and cloud-init that installs Docker + runs OpenSearch with a sane heap.
4. [ ] **Operator steps** (written): create Oracle account, generate API signing key + OCIDs
   (tenancy/user), `terraform apply` the OCI module, then run the reindex against the new endpoint.
5. [ ] **Rebuild + verify:** point the reindex/backfill scripts at the Oracle endpoint (basic auth),
   run `run-full-catalog-reindex.sh` (reads embeddings from DynamoDB, no re-embed), then
   `verify-count` → expect 2,231,142.
6. [ ] Flip `catalog_search_backend=opensearch` with the non-AWS endpoint; update README/docs that
   currently say "OpenSearch on AWS is live" (commit `d0663dd`) — that's now stale.

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
