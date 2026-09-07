# Design — Migrate OpenSearch off AWS to a self-hosted Oracle Cloud node

## 1. Context

Catalog search sits behind `CatalogSearchService` (interface + `search`/`findById`), selected by
`catalog.search.backend`. The prior migration built the OpenSearch implementation, the reindex
job, and an AWS Serverless client. This design keeps all of that and adds:

1. a **second client transport** (basic auth over HTTPS) for a self-hosted node,
2. a **disk-based quantization mode** so the index fits a small VM,
3. an **OCI Terraform module** that provisions the node, and
4. **tooling + docs** to load, verify, and operate it.

Nothing above the seam changes. DynamoDB `recipe-ai-dev-catalog-full` (2,231,142 recipes + 1024-d
embeddings) is the system of record; OpenSearch is a rebuildable derived index.

```
   HTTP ─▶ CatalogController ─▶ CatalogSearchService (seam, unchanged)
                                   ├── InAppCatalogSearchService     (default — CURRENTLY SERVING)
                                   └── OpenSearchCatalogSearchService (backend=opensearch)
                                                │
                        opensearch.auth ────────┤
                          sigv4 → AwsSdk2Transport (AWS, kept)
                          basic → ApacheHttpClient5Transport ──HTTPS+basic auth──▶ Oracle A1 VM
                                                                                    (Docker: OpenSearch 2.x)
                                                ▲
                          reindex (no re-embed) │  reads text + persisted vectors
                                                │
                                   DynamoDB recipe-ai-dev-catalog-full (source of truth)
```

## 2. Client transport selection

`OpenSearchConfig` (conditional on `catalog.search.backend=opensearch`) branches on
`opensearch.auth`:

- **`sigv4`** (default): existing `AwsSdk2Transport` over `AwsCrtHttpClient`, `DefaultCredentials`,
  region from `aws.region`, signing service from `opensearch.signing-service`. Unchanged.
- **`basic`**: `ApacheHttpClient5TransportBuilder` (the default opensearch-java 3.x transport) with
  a `BasicCredentialsProvider` for `opensearch.username`/`password`. Endpoint parsed into an
  Apache `HttpHost` (scheme/host/port). When `opensearch.tls-verify=false`, a trust-all
  `SSLContext` + `NoopHostnameVerifier` is installed via a pooling connection manager so a
  self-signed cert is accepted. Fail-fast when credentials are blank.

New properties on `OpenSearchProperties`: `auth`, `username`, `password`, `tlsVerify`.

### 2.1 Self-hosted implies a full cluster

`auth=basic` means a full OpenSearch cluster (not serverless). Two places key off this:

- `OpenSearchIndexProvisioner.buildSettings()` treats `auth=basic` as a managed cluster, so it
  sets the `index.knn.algo_param.ef_search` setting (serverless rejects it).
- `CatalogReindexRunner.serverless` is `false` when `auth=basic`, so it indexes with
  `catalogRecipeId` as `_id` → idempotent upserts. This removes the serverless no-upsert /
  PIT-reconciliation complexity for the self-hosted path.

## 3. Quantization / index mapping

`OpenSearchIndexProvisioner.embeddingField()` supports three modes on the 1024-dim `knn_vector`:

| Mode | Mapping | Memory | Host | Recall |
|---|---|---|---|---|
| `none` | Faiss HNSW, float | ~9 GB vectors | large | best |
| `fp16` | Faiss HNSW + `sq`/fp16 encoder | ~4.5 GB | **24 GB (default)** | ~= float |
| `byte` | `mode: on_disk`, `compression_level: 16x`, `data_type: float` | tiny in RAM, floats on disk + rescoring | **12 GB** | slightly lower, rescored |

Key decision: **`byte` is implemented as disk-based (`on_disk`) mode, not `data_type: byte`.**
`data_type: byte` would require lossily converting both the persisted `List<Double>` embeddings
and every query vector to int8 in application code, breaking portability. `on_disk` keeps
`data_type: float`, so the reindex and query paths are unchanged — OpenSearch compresses
internally and rescores the top hits from full-precision floats on disk. (`on_disk` is a
managed/self-hosted feature ≥ 2.17; it is not offered on AWS Serverless, which only ever used
`none`/`fp16`.)

Default `fp16` on the 24 GB host matches the config already validated at 2.2M on AWS, lowest risk.

## 4. OCI Terraform module (`infrastructure/modules/oci-opensearch`)

Opt-in via `enable` (`count = enable ? 1 : 0`). Providers: `oracle/oci ~> 6.0`, `hashicorp/tls`.

Resources when enabled:
- **Compute:** `oci_core_instance` `VM.Standard.A1.Flex`, `shape_config { ocpus, memory_in_gbs }`
  (default 2 / 24), public IP, boot volume (default 100 GB). Image: latest Oracle Linux 9
  aarch64 (looked up) unless an explicit OCID is given.
- **Network:** `oci_core_vcn` (10.10.0.0/16) + public `oci_core_subnet` + `internet_gateway` +
  `route_table` (0.0.0.0/0 → IGW) + `security_list`.
- **Security list:** egress all; ingress TCP 22 and TCP 9200 **from `admin_cidr` only**.
- **SSH key:** uses a supplied public key, else generates a `tls_private_key` (private PEM
  emitted as a sensitive output).
- **cloud-init** (`cloud-init.yaml.tftpl`): sets `vm.max_map_count`, installs Docker (Oracle Linux
  repo), opens firewalld for 9200, writes a docker-compose for `opensearchproject/opensearch:2.17.1`
  single-node with the security plugin (HTTPS + basic auth on 9200), `OPENSEARCH_INITIAL_ADMIN_PASSWORD`
  from a variable, heap `-Xms/-Xmx` = auto (≈ half RAM, capped 31 GB), memlock + nofile ulimits,
  data dir bind-mounted on the boot volume.
- **Preconditions:** fail plan if `admin_cidr` or `opensearch_admin_password` is empty.

Root wiring: `oci` + `tls` providers in `providers.tf` (creds from `oci_*` vars), the module in
`main.tf` (compartment defaults to the tenancy root when `oci_compartment_ocid` is blank), `oci_*`
variables, and `oci_opensearch_*` outputs (endpoint, public IP, SSH command, generated key PEM).

### 4.1 Secrets handling
- Non-secret OCIDs/fingerprint/region/admin CIDR → tracked `dev.tfvars`.
- OpenSearch admin password → sensitive var via gitignored `*.auto.tfvars` or
  `TF_VAR_oci_opensearch_admin_password`. `.gitignore` excludes `*.auto.tfvars` and `*.pem`.
- OCI API private key → local file at `oci_private_key_path` (`~/.oci/oci_api_key.pem`), never
  committed.

## 5. Load + verify tooling

`scripts/run-oracle-catalog-reindex.sh`: endpoint-agnostic (reads `OPENSEARCH_ENDPOINT/USERNAME/
PASSWORD` from env), curl-probes reachability + auth, then runs the Spring one-off runner with
`OPENSEARCH_AUTH=basic`, `OPENSEARCH_TLS_VERIFY=false`, `OPENSEARCH_SIGNING_SERVICE=es`:
recreate index → bulk load (upsert via `_id`) → backfill failures → `verify-count`
(OpenSearch `_count` vs DynamoDB, non-zero exit if short). Because writes are idempotent upserts,
a short run is just re-run — no serverless-style auto-backfill dance is required.

## 6. Serving config (dev)

```
CATALOG_SEARCH_BACKEND=opensearch
OPENSEARCH_AUTH=basic
OPENSEARCH_ENDPOINT=https://<oracle-ip>:9200
OPENSEARCH_USERNAME=admin
OPENSEARCH_PASSWORD=<secret>
OPENSEARCH_TLS_VERIFY=false
OPENSEARCH_KNN_QUANTIZATION=<fp16|byte>   # must match the index build
```

Local: `application-local.properties`. ECS: task-definition env/secrets — gated on Requirement 7.

## 7. Network reachability (open item)

ECS tasks run in **public subnets, `assign_public_ip=true`, no NAT gateway**, so their egress IP is
per-task and changes on deploy. There is no stable app IP to whitelist for 9200. Options, decided
before cutover:

1. **NAT gateway + Elastic IP** on the ECS side → open 9200 to that one EIP. Cleanest, but
   ~$32/mo (more than the node) and changes AWS networking.
2. **Reverse proxy / mTLS** fronting 9200. More moving parts.
3. **Laptop-only for now** (current): index is built + verifiable; the live app stays on `inapp`.

Until decided, `catalog_search_backend=inapp` in `dev.tfvars`. Never open 9200 to `0.0.0.0/0`.

## 8. Rollback & cost

- **Rollback:** flip `catalog_search_backend=inapp` (already the current state) — instant, no data
  loss. The small in-app catalog table is untouched.
- **Cost off:** `enable_oci_opensearch=false` + apply destroys the node. DynamoDB source of truth
  (~$7/mo) is retained so the index rebuilds anywhere with no re-embedding.
- **Recurring:** ~$13/mo for 2 OCPU / 24 GB PAYG (or $0 on the free 12 GB + `byte`).

## 9. Testing

- Unit: `OpenSearchIndexProvisionerTest` covers all three quantization mappings (byte = on_disk).
  `OpenSearchCatalogSearchServiceTest` covers query translation + keyword fallback.
- Build: `./mvnw test` (OpenSearch/config suites green). `terraform fmt -check` + `validate` on the
  module.
- Operator verification: the reindex script's `verify-count` gate (2,231,142) is the completeness
  proof; a self-signed-cert connectivity smoke test (`curl -k -u`) precedes the long run.
