# Runbook: stand up self-hosted OpenSearch on Oracle Cloud + load the catalog

Operator steps to provision the self-hosted OpenSearch node on Oracle Cloud (Ampere A1), load the
full 2.23M-recipe catalog into it from DynamoDB, and verify completeness. This replaces AWS
OpenSearch Serverless (deleted for cost). Background: `documents/NEXT-STEPS-oracle-opensearch.md`
and `documents/opensearch-implementation.md`.

The app code is already portable: `opensearch.auth=basic` selects an HTTPS basic-auth transport,
and `opensearch.knn.quantization=fp16|byte` picks the vector-memory strategy. The Terraform module
`infrastructure/modules/oci-opensearch` provisions the VM + network.

---

## 0. Decisions baked in

- **Shape:** `VM.Standard.A1.Flex`, **2 OCPU / 24 GB**, always on. ~$13/mo on Pay-As-You-Go
  (the extra 12 GB over the free 12 GB grant). Requires a **PAYG** account. To stay $0 on the pure
  free tier, set `oci_opensearch_memory_gb = 12` and `QUANTIZATION=byte` (see §7).
- **Quantization:** `fp16` (in-memory, high recall, validated at 2.2M on AWS). `byte` = disk-based
  (`on_disk`, 16x compression) fits a 12 GB box; no client-side vector conversion either way.
- **Auth:** HTTP basic auth over HTTPS. The node uses a self-signed cert, so the client runs with
  `opensearch.tls-verify=false` — safe because port 9200 is locked to your IP by the security list.
- **Network:** SSH (22) and OpenSearch (9200) are open ONLY to your admin IP. The reindex/verify
  run from your laptop. Live ECS→Oracle access is solved separately before cutover (§8).

---

## 1. One-time: Oracle account + API signing key

1. Create an Oracle Cloud account and **upgrade to Pay-As-You-Go** (required for 24 GB):
   Console → **Billing & Cost Management → Upgrade and Manage Payment** → add a payment method.
   The $300 / 30-day trial credit is applied first, so near-term cost is ~$0.
2. Create an API signing key: profile icon → **My profile → Tokens and keys → API keys →
   Add API key → Generate API key pair → Download private key**, then **Add**.
   - Save the private key to `~/.oci/oci_api_key.pem` and `chmod 600 ~/.oci/oci_api_key.pem`.
   - **Never paste the private key anywhere.** Only the config *preview* (OCIDs + fingerprint) is
     non-secret. If a private key is ever exposed, delete that key in the console and generate a
     new pair (the fingerprint changes; the user/tenancy/region do not).
3. From the config preview, collect: `user` OCID, `tenancy` OCID, `fingerprint`, `region`.

---

## 2. Provide the Terraform variables

Non-secret OCIDs/fingerprint/region + toggles go in `infrastructure/environments/dev.tfvars`
(tracked). The OpenSearch admin password is a **secret** and goes in a **gitignored**
`*.auto.tfvars` (never committed).

Add to `infrastructure/environments/dev.tfvars`:

```hcl
# --- Self-hosted OpenSearch on Oracle Cloud ---
enable_oci_opensearch = true
oci_tenancy_ocid      = "ocid1.tenancy.oc1..aaaa..."
oci_user_ocid         = "ocid1.user.oc1..aaaa..."
oci_fingerprint       = "69:59:91:1b:1a:e7:38:53:fe:60:33:d9:66:83:1e:d4"
oci_region            = "us-sanjose-1"
oci_private_key_path  = "~/.oci/oci_api_key.pem"
oci_admin_cidr        = "70.95.245.8/32"   # your admin IP (curl -s ifconfig.me)/32
# Shape defaults are 2 OCPU / 24 GB; override here for the free 12 GB fallback:
# oci_opensearch_memory_gb = 12
```

Create `infrastructure/oci-secrets.auto.tfvars` (gitignored — do NOT commit):

```hcl
# Must satisfy OpenSearch's strong-password policy (>=8 chars, upper/lower/digit/special).
oci_opensearch_admin_password = "<choose-a-strong-password>"
```

> The password can alternatively be exported as `TF_VAR_oci_opensearch_admin_password` so it never
> touches disk. It flows into the VM via cloud-init as `OPENSEARCH_INITIAL_ADMIN_PASSWORD`.

If your admin IP changes (residential IPs rotate), update `oci_admin_cidr` and
`terraform apply` — only the security list changes.

---

## 3. Provision the node

```bash
cd infrastructure
terraform init            # pulls the oci + tls providers on first run
terraform plan  -var-file=environments/dev.tfvars   # review: 1 VM + VCN/subnet/IGW/SL, no AWS changes
terraform apply -var-file=environments/dev.tfvars
```

Grab the outputs:

```bash
terraform output oci_opensearch_endpoint     # e.g. https://<public-ip>:9200
terraform output oci_opensearch_ssh_command  # ssh opc@<public-ip>
# If the module generated an SSH key (no oci_ssh_public_key supplied), save it:
terraform output -raw oci_opensearch_ssh_private_key_pem > ~/.ssh/oci_opensearch
chmod 600 ~/.ssh/oci_opensearch
```

cloud-init installs Docker and starts OpenSearch — allow **3–5 minutes** after apply. Verify:

```bash
ssh -i ~/.ssh/oci_opensearch opc@<public-ip> 'docker ps && docker logs --tail 20 opensearch'
# From your laptop (‑k because the cert is self-signed):
curl -k -u admin:'<password>' https://<public-ip>:9200
```

A JSON cluster banner = the node is up and basic auth works.

---

## 4. Load the catalog (reindex from DynamoDB — no re-embedding)

Embeddings live in DynamoDB (`recipe-ai-dev-catalog-full`); the reindex reads vectors from there
and never calls Bedrock.

```bash
export OPENSEARCH_ENDPOINT="https://<public-ip>:9200"
export OPENSEARCH_USERNAME="admin"
export OPENSEARCH_PASSWORD="<the admin password>"
# QUANTIZATION defaults to fp16 (24 GB). For a 12 GB box: export QUANTIZATION=byte
./scripts/run-oracle-catalog-reindex.sh
```

The script: probes the endpoint + auth, recreates the index with the chosen mapping, bulk-loads
~2.23M recipes (using `catalogRecipeId` as `_id`, so re-runs are idempotent upserts — no
serverless-style duplicate risk), backfills any failed ids, then verifies the count.

**Expected final line:** `Verify PASSED` with `2231142` docs == `2231142` in DynamoDB.

If it ends short, just re-run the script (idempotent) or run the backfill.

---

## 5. Point the live app at the node (dev)

Once the index is verified, run the backend against it with basic auth:

```
CATALOG_SEARCH_BACKEND=opensearch
OPENSEARCH_AUTH=basic
OPENSEARCH_ENDPOINT=https://<public-ip>:9200
OPENSEARCH_USERNAME=admin
OPENSEARCH_PASSWORD=<the admin password>
OPENSEARCH_TLS_VERIFY=false
OPENSEARCH_KNN_QUANTIZATION=<fp16|byte>   # MUST match what the index was built with
```

Locally, add these to `application-local.properties`. For ECS, the task definition needs these
wired as env vars / secrets — but first solve reachability (§8), because ECS cannot reach the
node while 9200 is locked to your admin IP.

---

## 6. Sanity checks

```bash
# doc count
curl -k -u admin:'<pw>' https://<ip>:9200/catalog-recipes/_count
# keyword search
curl -k -u admin:'<pw>' "https://<ip>:9200/catalog-recipes/_search?q=chicken&size=1"
# node heap / memory
ssh -i ~/.ssh/oci_opensearch opc@<ip> 'docker stats --no-stream opensearch'
```

---

## 7. Free-tier (12 GB, $0) fallback

If you skip PAYG or want $0:

```hcl
# dev.tfvars
oci_opensearch_memory_gb = 12
```

```bash
# reindex with disk-based quantization so the index fits 12 GB
export QUANTIZATION=byte
./scripts/run-oracle-catalog-reindex.sh
```

`byte` keeps a small compressed copy of vectors in RAM and the full-precision floats on disk,
rescoring the top hits — fits 12 GB at the cost of slightly higher query latency / marginally
lower recall. The app’s `OPENSEARCH_KNN_QUANTIZATION` must also be `byte` at serve time.

---

## 8. Before cutover: ECS → Oracle reachability (open item)

The ECS tasks run in **public subnets with dynamic public IPs and no NAT gateway**, so there is no
stable app egress IP to whitelist for port 9200. Options, to decide before the production cutover:

1. **NAT gateway + Elastic IP** on the ECS side, then open 9200 to that single EIP. Cleanest, but
   ~$32/mo (more than the node) and changes existing AWS networking.
2. **Reverse proxy / mTLS** in front of 9200. More moving parts.
3. Keep it laptop-only for now (current state): the index is built and verifiable; the live app
   stays on the in-app backend until reachability is chosen.

Until then, leave `catalog_search_backend=inapp` for the deployed app. Flip to `opensearch` only
after ECS can reach the node.

---

## 9. Teardown / cost off

```bash
# stop billing for the node (keeps DynamoDB source of truth intact)
# in dev.tfvars: enable_oci_opensearch = false
terraform apply -var-file=environments/dev.tfvars
```

DynamoDB `recipe-ai-dev-catalog-full` is the source of truth (~$7/mo). Keep it: the index rebuilds
from it anywhere with no re-embedding.
