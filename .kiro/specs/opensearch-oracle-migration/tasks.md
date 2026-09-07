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

- [ ] 8. Load + verify the index (OPERATOR — long batch run)
  - [ ] 8.1 Export `OPENSEARCH_ENDPOINT/USERNAME/PASSWORD` (+ `QUANTIZATION=fp16`, or `byte` for a
        12 GB box) and run `./scripts/run-oracle-catalog-reindex.sh`.
  - [ ] 8.2 Confirm the final `Verify PASSED` line: **2,231,142 == 2,231,142**.
  - _Requirements: 5.2, 5.3_

- [ ] 9. Decide ECS → Oracle reachability, then cut over (OPERATOR — open decision)
  - [ ] 9.1 Choose the app egress path: NAT gateway + Elastic IP (open 9200 to that EIP) vs reverse
        proxy vs keep laptop-only. Do NOT open 9200 to the internet.
  - [ ] 9.2 Extend the security list to the app's stable source; wire the serving env vars into the
        ECS task definition (`CATALOG_SEARCH_BACKEND=opensearch`, `OPENSEARCH_AUTH=basic`, endpoint,
        username/password as secrets, `OPENSEARCH_TLS_VERIFY=false`, matching `OPENSEARCH_KNN_QUANTIZATION`).
  - [ ] 9.3 Flip `catalog_search_backend=opensearch` in `dev.tfvars` and apply. Verify live search;
        keep `inapp` as the instant rollback.
  - _Requirements: 7.1, 7.2, 7.3_

## Verification summary (done items)
- Backend: `./mvnw -o compile` clean; OpenSearch + config test suites pass. The 6 unrelated
  property-test failures (AccountDeletion/Audit/DataExport) are pre-existing (confirmed on a clean
  stash) and outside this scope.
- Terraform: `fmt -check` clean, `validate` Success (oci 6.37.0 + tls 4.4.0 installed).
- Scripts: `zsh -n` syntax clean.
- Nothing has been applied to Oracle or AWS by this work; tasks 7–9 are the operator's to run.
