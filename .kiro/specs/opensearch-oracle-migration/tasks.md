# Implementation Plan — Migrate OpenSearch off AWS to a self-hosted Oracle Cloud node

Swap the catalog search host from AWS OpenSearch Serverless (deleted for cost) to a self-hosted
single-node OpenSearch on an Oracle Cloud Ampere A1 VM, behind the existing `CatalogSearchService`
seam. Make the app transport-pluggable (SigV4 or basic auth), add a disk-based quantization mode
for small hosts, provision the node with Terraform, and load + verify from DynamoDB with no
re-embedding. In-app stays the current live backend until ECS reachability is decided.

> **Status legend:** [x] done & verified in this repo · [ ] operator-run (needs the live account /
> a long batch run) · items under an operator task describe exactly what to run.
>
> **Decisions:** PAYG, `VM.Standard.A1.Flex` 2 OCPU / 24 GB (~$13/mo), `fp16` quantization; free
> $0 fallback is 12 GB + `byte`. Basic auth over HTTPS, self-signed cert, 9200 + SSH locked to
> admin IP `70.95.245.8/32`. OCI region `us-sanjose-1`; DynamoDB stays the `us-east-1` source of
> truth. Full background: `documents/NEXT-STEPS-oracle-opensearch.md`,
> `documents/RUNBOOK-oracle-opensearch.md`.

- [x] 1. Pluggable client transport (SigV4 | basic auth)
  - [x] 1.1 Add `opensearch.auth` (`sigv4|basic`), `opensearch.username`, `opensearch.password`,
        `opensearch.tls-verify` to `OpenSearchProperties`; env passthroughs in
        `application.properties` (`OPENSEARCH_AUTH/USERNAME/PASSWORD/TLS_VERIFY`).
  - [x] 1.2 Rewrite `OpenSearchConfig` to branch on `opensearch.auth`: keep the SigV4
        `AwsSdk2Transport` path; add a basic-auth path via `ApacheHttpClient5TransportBuilder` +
        `BasicCredentialsProvider` over HTTPS. Parse the endpoint into an Apache `HttpHost`.
  - [x] 1.3 Self-signed cert support: when `auth=basic` and `tls-verify=false`, install a
        trust-all `SSLContext` + `NoopHostnameVerifier` via a pooling connection manager.
  - [x] 1.4 Fail-fast when `auth=basic` and username/password are blank; no client bean when
        `catalog.search.backend != opensearch` (default in-app pulls in nothing).
  - [x] Verified: `./mvnw -o compile` clean; the Apache hc5 classes ship with opensearch-java 3.9.0.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Quantization that fits the target host
  - [x] 2.1 Add `byte` mode to `OpenSearchIndexProvisioner.embeddingField()` as disk-based
        (`mode: on_disk`, `compression_level: 16x`, `data_type: float`) — no client-side vector
        conversion; fits a 12 GB box. Keep `none`/`fp16`; reject unknown values.
  - [x] 2.2 `buildSettings()` treats `auth=basic` as a managed cluster (sets `ef_search`).
  - [x] 2.3 Update the quantization doc comments/property to `none | fp16 | byte`.
  - [x] Tests: `OpenSearchIndexProvisionerTest.quantizationByte_usesDiskBasedOnDiskMode` (and the
        existing none/fp16/unknown cases) pass.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 3. Idempotent upsert on the self-hosted node
  - [x] 3.1 `CatalogReindexRunner.serverless` is `false` when `auth=basic`, so it indexes with
        `catalogRecipeId` as `_id` (real upsert); serverless auto-id path retained for SigV4.
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 4. OCI Terraform module (opt-in, cost-bounded)
  - [x] 4.1 Create `infrastructure/modules/oci-opensearch` (main/variables/outputs +
        `cloud-init.yaml.tftpl`), all gated by `enable` (`count = enable ? 1 : 0`).
  - [x] 4.2 Compute: `VM.Standard.A1.Flex`, `shape_config` OCPU/memory (default 2/24), public IP,
        boot volume (default 100 GB), Oracle Linux 9 aarch64 image lookup (or explicit OCID).
  - [x] 4.3 Network: VCN + public subnet + internet gateway + route table + security list; ingress
        22 and 9200 restricted to `admin_cidr` (never 0.0.0.0/0); egress all.
  - [x] 4.4 cloud-init: `vm.max_map_count`, install Docker, open firewalld 9200, run
        `opensearchproject/opensearch:2.17.1` single-node (security plugin, HTTPS + basic auth),
        admin password from a sensitive var, auto heap (≈ half RAM, cap 31 GB), memlock/nofile
        ulimits, data dir on the boot volume.
  - [x] 4.5 SSH key: use supplied public key, else generate a `tls_private_key` (private PEM as a
        sensitive output). Plan-time preconditions: `admin_cidr` and admin password required.
  - [x] 4.6 Root wiring: `oci` + `tls` providers in `providers.tf` (creds from `oci_*` vars);
        module in `main.tf`; `oci_*` variables; `oci_opensearch_*` outputs.
  - [x] 4.7 Secrets: `.gitignore` excludes `*.auto.tfvars` (+ existing `*.pem`); password via
        sensitive var / `TF_VAR_...`; OCI private key referenced by file path only.
  - [x] Verified: `terraform init -backend=false` (oci 6.37.0, tls 4.4.0), `terraform fmt -check`
        clean, `terraform validate` = Success.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 5. Load + verify tooling
  - [x] 5.1 Add `scripts/run-oracle-catalog-reindex.sh`: basic-auth, endpoint from env, curl
        reachability/auth probe, recreate → bulk load (upsert) → backfill → `verify-count`
        (non-zero exit if short). `zsh -n` syntax clean.
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 6. Documentation / truthful status
  - [x] 6.1 Write `documents/RUNBOOK-oracle-opensearch.md` (account + API key, tfvars incl.
        gitignored secret password, apply, reindex, serve config, 12 GB fallback, ECS
        reachability open item, teardown).
  - [x] 6.2 De-stale README (intro, feature bullet, datasets, swappable-backend section + table,
        infra table, modules tree, tech-stack row): AWS "live" → "in-app now; migrating to
        self-hosted Oracle; Serverless deleted for cost".
  - [x] 6.3 Update `documents/NEXT-STEPS-oracle-opensearch.md` plan to reflect completed work +
        the ECS reachability open item.
  - _Requirements: 6.1, 6.2_

- [x] 7. Provision the Oracle node (OPERATOR — needs the live PAYG account)
  - [x] 7.1 Added the OCI vars to `infrastructure/environments/dev.tfvars` (enable + OCIDs +
        fingerprint + region `us-sanjose-1` + private-key path + `oci_admin_cidr=70.95.245.8/32`,
        shape 2 OCPU / 24 GB); admin password in gitignored `infrastructure/oci-secrets.auto.tfvars`.
  - [x] 7.2 `terraform apply` succeeded: **9 added, 3 changed, 12 destroyed** (Oracle node created,
        ECS flipped to `inapp`, dead AWS Serverless resources cleaned up). No DynamoDB changes.
  - [x] 7.3 Node live at **`https://170.9.60.251:9200`**. SSH key saved to `~/.ssh/oci_opensearch`
        (chmod 600). cloud-init completed (Docker up, `opensearch` container running). Verified from
        laptop: `curl -k -u admin ...` → HTTP 200, OpenSearch 2.17.1 cluster banner. Basic auth +
        self-signed TLS path confirmed working end to end.
  - _Requirements: 4.1, 4.2, 4.3_

- [x] 8. Load + verify the index (OPERATOR — long batch run)
  - [x] 8.1 Ran `./scripts/run-oracle-catalog-reindex.sh` (fp16). Load took ~7h15m (~12:13→19:29),
        ~75-89 docs/sec, bound by the DynamoDB scan. **`Reindex complete: 2231142 seen, 2231142
        indexed, 0 skipped, 0 failed`** — clean on the first pass, no backfill needed (self-hosted
        node has fixed capacity, so none of the AWS-serverless throttle-drops occurred; AWS had
        ~65K constant failures).
  - [x] 8.2 Independent live `_count` = **2231142** == DynamoDB 2231142. Keyword search returns
        hits; sample docs carry catalogRecipeId/title/dietaryTags. Index complete + functional.
  - Mid-run fix: root FS was only 30 GB (Oracle Linux default); ran `oci-growfs` online to expand
    to 83 GB (100 GB volume) — load continued uninterrupted. Now automated in cloud-init.
  - _Requirements: 5.2, 5.3_

- [x] 9. Decide ECS → Oracle reachability, then cut over (OPERATOR — DONE)
  - [x] 9.1 Chose **NAT gateway + Elastic IP** (single-AZ, ~$33/mo, budget freed by cutting a NAT
        on the portfolio app). Also moved ECS into private subnets for the AWS-recommended posture
        (ALB-only public exposure). 9200 NOT opened to the internet — locked to the NAT EIP.
  - [x] 9.2 Extended the OCI security list to allow the NAT EIP (`100.51.158.37`) on 9200. ECS task
        def wired: `CATALOG_SEARCH_BACKEND=opensearch`, `OPENSEARCH_AUTH=basic`, endpoint auto =
        OCI node, `OPENSEARCH_USERNAME=admin`, `OPENSEARCH_PASSWORD` from Secrets Manager (secret,
        not plaintext), `OPENSEARCH_TLS_VERIFY=false`, `OPENSEARCH_KNN_QUANTIZATION=fp16`.
  - [x] 9.3 Applied infra (8 add / 5 change / 1 destroy), merged to main (PR #43) → CI built+pushed
        the new image (`a062114`) with the basic-auth `OpenSearchConfig` → ECS redeployed. New task
        healthy in a private subnet (`10.0.11.140`), logs confirm `OpenSearch client (basic auth)`
        to the Oracle node with no errors. **Live search verified in the browser** (keyword +
        semantic both work against the 2.23M index). Rollback: flip `catalog_search_backend=inapp`.
  - Note: first search after a fresh deploy has a ~10s cold start (Bedrock client warm-up + first
    k-NN loads the HNSW graph from disk into page cache + JVM JIT). One-time; subsequent queries
    are sub-second. Optional future fix: a startup warm-up query. Not a bug.
  - Safety fixes added this session: `deployment_circuit_breaker{rollback=true}` on both ECS
    services (auto-rollback on a bad deploy) and `depends_on = [module.networking]` on the ECS
    module (NAT/route live before tasks move to private subnets).
  - _Requirements: 7.1, 7.2, 7.3_

## Cutover complete (2026-09-08)
All 9 tasks done. AWS OpenSearch Serverless (~$240/mo forecast) → self-hosted Oracle A1 node
(~$0-13/mo). 2,231,142 docs indexed + verified, live search on the new backend, ECS hardened into
private subnets behind a NAT. DynamoDB remains the source of truth; rollback is a one-line tfvars
flip.

## Verification summary (done items)
- Backend: `./mvnw -o compile` clean; OpenSearch + config test suites pass. The 6 unrelated
  property-test failures (AccountDeletion/Audit/DataExport) are pre-existing (confirmed on a clean
  stash) and outside this scope.
- Terraform: `fmt -check` clean, `validate` Success (oci 6.37.0 + tls 4.4.0 installed).
- Scripts: `zsh -n` syntax clean.
- Nothing has been applied to Oracle or AWS by this work; tasks 7–9 are the operator's to run.
