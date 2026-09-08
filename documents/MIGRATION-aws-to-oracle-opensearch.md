# Migration: AWS OpenSearch Serverless → self-hosted OpenSearch on Oracle Cloud

A detailed record of **why** and **how** catalog search was moved off Amazon OpenSearch
Serverless to a self-hosted OpenSearch node on Oracle Cloud, the cost reasoning that drove it, and
the network hardening (NAT gateway + private subnets) that came with the cutover.

This is a decision/thought-process document. For the mechanical operator steps see
`documents/RUNBOOK-oracle-opensearch.md`; for the original AWS build and its post-mortem see
`documents/opensearch-implementation.md`; for the formal spec see
`.kiro/specs/opensearch-oracle-migration/`.

---

## 1. TL;DR

- Catalog search (a ~2.23M-recipe hybrid keyword + vector index) originally ran on **Amazon
  OpenSearch Serverless**. To keep the ~2M-vector index available, it held roughly **6 OCUs warm
  continuously and did not scale back down** — at **$0.24/OCU-hour** that is **~$1,050/month**,
  against a hobby-project budget of about **$15/month**.
- We **deleted the serverless collection** and re-hosted the identical index on a **single
  Oracle Cloud Ampere A1 VM** running OpenSearch in Docker. Recurring cost dropped to **~$0–13/month**.
- Because the embeddings are the durable source of truth in DynamoDB, the index was **rebuilt with
  no re-embedding**, and the app was made transport-portable so the *same code* talks to either
  Amazon OpenSearch (SigV4) or the self-hosted node (basic auth over HTTPS).
- At cutover we also **moved the ECS tasks into private subnets behind a NAT gateway** — this both
  gave the app a stable egress IP to reach the Oracle node securely and upgraded the app to the
  AWS-recommended "ALB is the only public entry point" network posture.

---

## 2. Why we migrated — the cost problem

> **How to read the numbers in this section.** We separate what is **verified** from what is
> **estimated/reconstructed**, because the collection has since been deleted and some exact
> historical figures can no longer be pulled from AWS.
>
> - **Verified:** the OpenSearch Serverless OCU rate is **$0.24 per OCU-hour** in us-east-1
>   (AWS pricing / re:Post). An OCU is **1 vCPU + 6 GB RAM + 120 GB storage** (AWS docs). After we
>   deleted the collection, the daily OpenSearch charge dropped to **$0.00** in Cost Explorer.
> - **Estimated/reconstructed:** the exact OCU count the collection held (**~6**, from the
>   operator's recollection of the console) and therefore the exact monthly dollar figure. The
>   collection is gone, so we cannot re-read the historical OCU count from AWS; the dollar figures
>   below are computed from that ~6-OCU recollection × the verified rate.
> - **Correction:** an earlier draft of this doc said ~$240/month. **That was wrong** — it reasoned
>   from the reduced *entry-minimum* (0.5 OCU each) instead of the ~6 OCUs actually held warm for a
>   2M-vector index. The honest figure is roughly an order of magnitude higher; see below.

### 2.1 How OpenSearch Serverless bills, and why "scale to zero" didn't apply

OpenSearch Serverless bills by **OCU** (OpenSearch Compute Unit), with separate pools for indexing
and search. Its headline feature is "scale to zero / no idle compute costs." That is real for
*bursty traffic on a small index* — but it did **not** apply to our workload, and understanding why
is the whole story.

A **vector (VECTORSEARCH) collection** answers semantic queries by walking an **HNSW graph** built
over the embeddings, and that graph must stay **resident in memory** to be fast. Our index is
**~2.23M vectors × 1024 dimensions** — at full float, ~9 GB of raw vector data before graph
overhead, so the in-memory footprint runs into the tens of GB. Serverless provisions enough OCUs to
hold that resident footprint, and it will **not** drop below what's needed to keep the graph in
memory (evicting it would make the next search slow). So for a large resident vector index, the
floor is not zero — it's "however many OCUs it takes to hold the graph, 24/7."

### 2.2 What "6 OCUs to keep the index warm" actually means (the unit, precisely)

A natural way to describe this is "AWS was using ~6 CPUs to keep my 2M index warm." That's *nearly*
right, but the precise unit is **6 OCUs**, and an OCU is more than a CPU:

> **1 OCU = 1 vCPU + 6 GB RAM + 120 GB storage** — bundled together. You cannot buy the vCPU and the
> RAM separately.

So **~6 OCUs ≈ 6 vCPUs *and* ~36 GB of RAM**, held resident around the clock. And the thing that
actually forced the count was the **RAM, not the CPU**: those units existed to keep ~tens of GB of
HNSW vector graph in memory, not to service query CPU (traffic was minimal). We were effectively
renting **~36 GB of always-on memory to hold one index** — priced as if it were 6 units of elastic
compute running 24/7.

This is precisely the mismatch. The correct hardware for "hold a ~10–20 GB vector graph in memory
for a low-traffic app" is a single small VM with enough RAM — which is exactly what the Oracle node
is (24 GB, with `fp16` quantization halving the vector memory), for a tiny fixed price.

### 2.3 The cost math

Using the **verified** $0.24/OCU-hour rate × 730 hours/month = **$175.20 per OCU per month**:

| OCUs held warm 24/7 | Monthly cost | Confidence |
|---|---|---|
| 4 OCU (a common prod minimum: 2 indexing + 2 search) | ~$701 | reference point |
| **~6 OCU (operator's recollection of our footprint)** | **~$1,051** | **estimate** (see note) |
| 6.5 OCU | ~$1,139 | upper reference |

The rate and the per-OCU arithmetic are verified; the **~6-OCU count is a recollection**, so treat
the ~$1,050 as a best estimate with a plausible range of roughly **$700–$1,140/month** depending on
the exact OCU count. This is consistent with figures other AWS users report for warm vector
collections (e.g. ~$691/month for a 4-OCU setup). Either way, the conclusion is unchanged: for a
**large, always-resident, low-traffic** index, the meter ran continuously in the **high hundreds to
~$1,000+/month** range — roughly **50–70× a ~$15/month hobby budget**.

### 2.4 What we can state with certainty

Stripping out the estimated pieces, here is what is **not** in doubt:
- The rate was **$0.24/OCU-hour**, and a vector collection of this size **cannot** run at the
  entry-minimum — it needs several OCUs of resident memory, so the real cost was **many hundreds of
  dollars per month**, not tens.
- After deleting the collection, the OpenSearch charge went to **$0.00/day** (verified in Cost
  Explorer).
- The replacement Oracle node is a **fixed ~$0–13/month** regardless of the exact prior figure.

So even on the most conservative reading, the migration removed a **several-hundred-dollar-per-month**
recurring charge for a feature that now costs about the price of a coffee — while the search behavior
is unchanged.

---

## 3. The options we weighed

| Option | Monthly cost | Notes |
|---|---|---|
| Keep AWS OpenSearch Serverless | **~$1,050** (6 warm OCUs) | Rejected: ~70x the budget for a low-traffic index |
| AWS OpenSearch **managed domain** (e.g. a small `t3.small.search`) | ~$25–50+ | Cheaper than serverless but still AWS-metered, and small instances struggle with a 2.2M vector graph |
| **Oracle Cloud Ampere A1 (self-hosted)** | **~$0–13** | Chosen. Always-Free-eligible ARM compute; fixed price; full control |
| Hetzner / other cheap VPS (~16 GB) | ~$16 | Viable fallback if Oracle's free tier proved too tight |

**Why Oracle Ampere A1 won:**
- Oracle's **Always Free** ARM allowance is genuinely unlimited in time (not a 30-day trial), and
  even on a paid Pay-As-You-Go account a small A1 instance largely sits inside the free monthly grant.
- ARM (aarch64) matched our stack (the backend already builds ARM64 images), so no architecture
  friction.
- A **single fixed-price VM** turns an unpredictable per-OCU meter into a flat, tiny, predictable cost.

**The one real constraint** (Oracle halved the Always Free A1 allowance to **2 OCPU / 12 GB** on
2026-06-15): 12 GB is tight for a 2.23M fp16 vector index. We resolved this by making quantization a
knob (see §5) — `fp16` on a 24 GB PAYG shape (~$13/mo, what we run), with a `byte`/disk-based
fallback that fits the free 12 GB shape ($0) if desired.

---

## 4. What made the migration cheap and safe: DynamoDB as the source of truth

The migration was low-risk for one structural reason: **the OpenSearch index is derived, not
authoritative.** Every recipe's text, dietary tags, and its 1024-dim Titan V2 **embedding** are
persisted in the DynamoDB `recipe-ai-dev-catalog-full` table (2,231,142 items). OpenSearch is just a
search index built from that table.

Consequences:
- The index could be **rebuilt on any host with no re-embedding** — the reindex reads vectors
  straight from DynamoDB and bulk-loads them. No Bedrock cost, no ~17-hour re-embed.
- Deleting the AWS collection destroyed **nothing irreplaceable**. Worst case was "rebuild the index
  elsewhere."
- Rollback stays trivial: the in-app search backend remains a config flip away.

---

## 5. How we did it — the four workstreams

### 5.1 App portability (transport + quantization)

The app already put search behind a `CatalogSearchService` seam, so no controller/DTO/frontend
changes were needed. We made the OpenSearch client itself portable:

- **`OpenSearchConfig`** now selects its transport by `opensearch.auth`:
  - `sigv4` → AWS SigV4-signed requests (`AwsSdk2Transport`), unchanged for any AWS deployment.
  - `basic` → HTTP **basic auth over HTTPS** via the Apache HttpClient5 transport, for the
    self-hosted node, with an optional **TLS-verify toggle** so a self-signed certificate is
    accepted (safe because the port is IP-locked; see §6).
- **Quantization** (`opensearch.knn.quantization`) gained a third mode:
  - `none` / `fp16` — in-memory Faiss HNSW (what AWS used; `fp16` ≈ half the vector memory).
  - `byte` — **disk-based mode** (`mode: on_disk`, 16x compression). Crucially implemented as
    `data_type: float` so **no lossy client-side vector conversion** is needed — OpenSearch
    compresses internally and rescores top hits from full-precision floats on disk. This is what
    lets the full index fit a 12 GB box.
- **Self-hosted implies a full cluster**, so with `auth=basic` the reindex indexes each document
  with `catalogRecipeId` as its `_id` — a real **idempotent upsert**. This removed all the
  serverless-only workarounds (auto-generated ids, PIT-only reconciliation) and made reruns safe.

A subtle but important fix surfaced at first index creation: OpenSearch 2.17's Faiss engine
**rejects `space_type: cosinesimil`** for an HNSW field. Because Titan V2 embeddings are
L2-normalized (unit length), **inner product is mathematically identical to cosine similarity** on
them, so we switched the space to `innerproduct` with no change in ranking and no re-embedding.

### 5.2 Terraform module for the Oracle node

A new opt-in module (`infrastructure/modules/oci-opensearch`) provisions, all gated so the default
deployment creates nothing:
- A `VM.Standard.A1.Flex` VM (default **2 OCPU / 24 GB**; shape is a variable).
- A VCN + public subnet + internet gateway + route table + **security list** (SSH 22 and the
  OpenSearch API 9200 restricted to specific source IPs — never `0.0.0.0/0`).
- A 100 GB boot volume, and **cloud-init** that installs Docker and runs single-node OpenSearch 2.17
  with the security plugin (HTTPS + basic auth on 9200), a sane JVM heap, and `oci-growfs` to expand
  the root filesystem to the full volume (Oracle Linux images only provision ~30 GB by default — a
  gotcha we hit mid-reindex and then automated away).

### 5.3 Rebuild + verify

`scripts/run-oracle-catalog-reindex.sh` points the reindex at the Oracle endpoint over basic auth,
recreates the index, bulk-loads all recipes from DynamoDB (no re-embed), backfills any failures, and
verifies completeness. The result:

```
Reindex complete: 2231142 seen, 2231142 indexed, 0 skipped, 0 failed
```

**Zero failures on the first pass** — a stark contrast to the AWS reindex, which suffered ~65,000
constant failures from serverless indexing-OCU throttling. A fixed-capacity node simply doesn't
throttle. An independent `_count` against the live node confirmed **2,231,142 == 2,231,142**.

### 5.4 Cutover

We flipped `catalog_search_backend=opensearch` with `opensearch.auth=basic`, wired the endpoint,
username, and (secret) password into the ECS task, shipped a new container image (the deployed image
predated the basic-auth code — a real gotcha caught during verification), and confirmed live search
works end to end. Rollback remains a one-line `catalog_search_backend=inapp` flip.

---

## 6. The NAT gateway + private subnets (security & reachability)

### 6.1 The problem

The ECS tasks originally ran in **public subnets with dynamic public IPs and no NAT gateway**. That
created a reachability problem for the cutover: the self-hosted node's port 9200 is locked by the
OCI security list to specific source IPs, but a Fargate task's public IP **changes on every
deployment** — there was no stable app IP to whitelist. It also meant the app containers, while
gated by a tight security group (ingress only from the ALB), still had routable public IPs — one
misconfiguration away from exposure.

### 6.2 The decision

We considered a $0 overlay network (Tailscale) and a NAT gateway. The NAT gateway won **because it
solves two problems with one spend**:

1. **Stable egress IP** — all private-subnet traffic leaves through the NAT gateway's single
   **Elastic IP**. We whitelist that one EIP on the Oracle node's 9200 rule, and the
   dynamic-Fargate-IP problem disappears.
2. **The AWS-recommended network posture** — moving ECS into **private subnets** (with the ALB as
   the only public entry point) means the app containers have **no public IPs at all**. Inbound is
   ALB-only; outbound (to Bedrock, DynamoDB, S3, ECR, Secrets Manager, and the Oracle node) flows
   through the NAT.

Budget was freed by cutting an unused NAT gateway on a *different* app in the same AWS account, so
adding one here was cost-neutral in spirit. A single-AZ NAT (~$33/mo) was chosen over one-per-AZ HA
as an acceptable dev tradeoff.

### 6.3 What was built

- Networking module: a **single NAT gateway + Elastic IP**, a private route table
  (`0.0.0.0/0` → NAT), and the private subnets associated to it.
- ECS: both services moved to **private subnets** with `assign_public_ip = false`, plus a
  **deployment circuit breaker** (`rollback = true`) so a bad deploy auto-reverts to the last-good
  task definition, and an explicit `depends_on` so the NAT egress path is live before any task
  moves (tasks pull their image and read secrets at startup over the NAT).
- OCI security list: the NAT's Elastic IP added to the 9200 ingress rule alongside the admin IP.
- The OpenSearch admin password is stored in **AWS Secrets Manager** and injected into ECS as a
  **secret** (never plaintext in the task definition).

### 6.4 Data flow after cutover

```
   Internet ──▶ ALB (public subnets, only public entry) ──▶ ECS tasks (PRIVATE subnets, no public IP)
                                                                    │  egress
                                                                    ▼
                                                            NAT gateway (stable Elastic IP)
                                                                    │
                          ┌─────────────────────────────────────────┼───────────────┐
                          ▼                                          ▼               ▼
                   Oracle OpenSearch node                     AWS APIs         (Bedrock, S3,
                   (9200, basic auth, IP-locked               (DynamoDB,        ECR, Secrets…)
                    to the NAT EIP)                            source of truth)
```

---

## 7. Cost outcome

| | AWS OpenSearch Serverless | Self-hosted Oracle A1 |
|---|---|---|
| Recurring cost (6 warm OCUs) | **~$1,050/month** | **~$0–13/month** |
| Range (4–6.5 OCU) | ~$700–$1,140/month | flat (fixed VM) |
| Verified after cutover | **$0** (collection deleted) | ~$0–13 (see below) |
| Billing model | per-OCU meter ($0.24/OCU-hr), stays warm | fixed VM price |

**Oracle recurring cost:** the node is a 2 OCPU / 24 GB A1 instance running 24/7. On Oracle's
metered Always Free grant (3,000 OCPU-hrs + 18,000 GB-hrs/month) a single such instance sits at or
near **$0**; if the 24 GB is billed against the reduced 12 GB free cap, the overage is about
**$13/month**. The 100 GB boot volume is within the 200 GB free block-storage allowance, and search
egress is far under the 10 TB free tier — both **$0**. The exact figure is confirmed via Oracle's
Cost Analysis dashboard.

**The NAT gateway** adds ~$33/month on the AWS side, but it is a *security* investment (private
subnets) as much as a reachability one, and it was offset by removing an unused NAT on another app.

**Net:** the specific line item this project set out to control — catalog search — went from
**~$1,050/month** (6 warm OCUs at $0.24/OCU-hr) to a flat **~$0–13/month**, a **~99% reduction**,
while the search feature itself is unchanged (and, with a fixed-capacity node, actually *more*
reliable to reindex).

---

## 8. Lessons / durable takeaways

- **Serverless is not automatically cheap.** For a *large, always-resident, low-traffic* index,
  a fixed-price host beats a per-unit meter by a wide margin. Match the pricing model to the traffic
  shape.
- **Keep the system of record separate from the derived index.** Because embeddings lived in
  DynamoDB, the expensive search layer became disposable and rebuildable anywhere — that single
  design choice is what made this migration cheap and reversible.
- **Design for portability behind a seam.** The `CatalogSearchService` interface and a pluggable
  client transport meant swapping the entire search host was a config + client change, not a rewrite.
- **A fixed-capacity node removes a whole class of failure.** Zero reindex failures vs ~65,000 on
  serverless — no throttling, real upserts, simpler recovery.
- **When you must add cost, make it earn its keep twice.** The NAT gateway solved reachability *and*
  hardened the network posture; a single-purpose spend would have been harder to justify.
- **Verify against reality, not logs.** The stale-image and the `cosinesimil` gotchas were both
  caught by checking the actual running system, not by trusting that "the plan looked right."
