#!/usr/bin/env zsh
#
# Rebuild the full catalog index on the SELF-HOSTED OpenSearch node (Oracle Cloud Ampere A1),
# end to end, over HTTPS basic auth. Reads embeddings from DynamoDB (NO Bedrock / no re-embed).
#
# Why this is simpler than the AWS serverless reindex (scripts/run-full-catalog-reindex.sh):
# a self-hosted OpenSearch cluster supports a CUSTOM document _id, so we index each recipe with
# catalogRecipeId AS its _id — that makes every write an idempotent UPSERT. Re-running the reindex
# just overwrites, never duplicates, so there is NO need for the serverless-only "recreate once,
# then auto-backfill only the failed ids" dance. If a run ends with failures, you simply run it
# again (or run the backfill) and the already-present docs are harmlessly overwritten.
#
# How to run (from the project root):
#   export OPENSEARCH_ENDPOINT="https://<oracle-public-ip>:9200"   # from `terraform output oci_opensearch_endpoint`
#   export OPENSEARCH_USERNAME="admin"
#   export OPENSEARCH_PASSWORD="<the OpenSearch admin password you set>"
#   ./scripts/run-oracle-catalog-reindex.sh
#
# Optional overrides (env):
#   QUANTIZATION      fp16 (default; needs ~24 GB RAM) | byte (on_disk 16x; fits a 12 GB box) | none
#   CONCURRENCY       in-flight bulk requests (default 4)
#   BATCH_SIZE        docs per bulk request (default 1000)
#   TLS_VERIFY        false (default; the node uses a self-signed cert behind an IP-locked port)
#
# Self-caffeinates so macOS won't sleep mid-run. Keep the Mac plugged in.
#
set -euo pipefail

# Self-caffeinate (re-exec under caffeinate once).
if [[ -z "${REINDEX_CAFFEINATED:-}" ]] && command -v caffeinate >/dev/null 2>&1; then
  echo "☕ Re-launching under caffeinate to keep the Mac awake for the full run..."
  export REINDEX_CAFFEINATED=1
  exec caffeinate -ims "$0" "$@"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BACKEND_DIR="$PROJECT_DIR/backend"
JAR="$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="$LOG_DIR/oracle-catalog-reindex-$TIMESTAMP.log"
FAILED_IDS_FILE="$LOG_DIR/oracle-reindex-failed-ids-$TIMESTAMP.txt"

# ── Configuration ────────────────────────────────────────────────────────────
# AWS is still needed for the DynamoDB source of truth (embeddings live there).
AWS_REGION="${AWS_REGION:-us-east-1}"
COGNITO_ISSUER_URI="${COGNITO_ISSUER_URI:-https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5je5ZFisi}"
CATALOG_FULL_TABLE="${CATALOG_FULL_TABLE:-recipe-ai-dev-catalog-full}"
QUANTIZATION="${QUANTIZATION:-fp16}"
BATCH_SIZE="${BATCH_SIZE:-1000}"
CONCURRENCY="${CONCURRENCY:-4}"
TLS_VERIFY="${TLS_VERIFY:-false}"

# ── Required env: the self-hosted endpoint + basic-auth creds ────────────────
: "${OPENSEARCH_ENDPOINT:?Set OPENSEARCH_ENDPOINT, e.g. https://<oracle-ip>:9200 (terraform output oci_opensearch_endpoint)}"
: "${OPENSEARCH_USERNAME:?Set OPENSEARCH_USERNAME (e.g. admin)}"
: "${OPENSEARCH_PASSWORD:?Set OPENSEARCH_PASSWORD (the OpenSearch admin password)}"

# ── Prerequisite checks ─────────────────────────────────────────────────────
echo "🔍 Checking prerequisites..."
if ! aws sts get-caller-identity &>/dev/null; then
  echo "❌ AWS credentials not configured (needed to read embeddings from DynamoDB). Run 'aws configure'."
  exit 1
fi
echo "  ✅ AWS credentials OK"

# Reachability + auth smoke test against the node before the long run.
echo "  🔌 Probing $OPENSEARCH_ENDPOINT ..."
CURL_TLS_FLAG=()
[[ "$TLS_VERIFY" == "false" ]] && CURL_TLS_FLAG=(-k)
if ! curl -sS "${CURL_TLS_FLAG[@]}" -u "$OPENSEARCH_USERNAME:$OPENSEARCH_PASSWORD" \
     "$OPENSEARCH_ENDPOINT" -o /dev/null -w "%{http_code}" | grep -q "200"; then
  echo "❌ Could not reach/authenticate to $OPENSEARCH_ENDPOINT."
  echo "   Checks: node is up (ssh in, 'docker ps'), your IP is in the OCI security list (9200),"
  echo "   and OPENSEARCH_USERNAME/PASSWORD are correct."
  exit 1
fi
echo "  ✅ OpenSearch reachable + basic auth OK"

echo "  🔨 Building backend JAR (fresh, so it matches current source)..."
(cd "$BACKEND_DIR" && ./mvnw -q package -DskipTests)
if [[ ! -f "$JAR" ]]; then
  echo "❌ Build failed. Fix errors and re-run."
  exit 1
fi
echo "  ✅ JAR built (fresh)"

mkdir -p "$LOG_DIR"
echo ""
echo "📋 Reindexing $CATALOG_FULL_TABLE → self-hosted OpenSearch ($OPENSEARCH_ENDPOINT)"
echo "   auth=basic, tlsVerify=$TLS_VERIFY, quantization=$QUANTIZATION, concurrency=$CONCURRENCY"
echo "   Recreates the index, then bulk-indexes ~2.23M recipes (catalogRecipeId as _id = upsert)."
echo "   Logging to: $LOG_FILE   (watch: grep 'Reindex progress' $LOG_FILE | tail -3)"
echo ""
echo "Starting in 5 seconds... (Ctrl+C to abort)"
sleep 5

echo "🚀 Starting reindex at $(date)"
echo ""

# ── Full recreate reindex ─────────────────────────────────────────────────────
set +e
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
OPENSEARCH_AUTH="basic" \
OPENSEARCH_ENDPOINT="$OPENSEARCH_ENDPOINT" \
OPENSEARCH_USERNAME="$OPENSEARCH_USERNAME" \
OPENSEARCH_PASSWORD="$OPENSEARCH_PASSWORD" \
OPENSEARCH_TLS_VERIFY="$TLS_VERIFY" \
OPENSEARCH_SIGNING_SERVICE="es" \
java -Xmx3g -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.knn.quantization="$QUANTIZATION" \
  --catalog.reindex.enabled=true \
  --catalog.reindex.recreate-index=true \
  --catalog.reindex.batch-size=$BATCH_SIZE \
  --catalog.reindex.concurrency=$CONCURRENCY \
  --catalog.reindex.failed-ids-file="$FAILED_IDS_FILE" \
  --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
  2>&1 | tee "$LOG_FILE"
REINDEX_EXIT=${pipestatus[1]}
set -e

echo ""
echo "── Reindex pass finished (exit $REINDEX_EXIT) at $(date) ──"
grep "Reindex complete" "$LOG_FILE" | tail -1 || true

# On a self-hosted node, re-running is an idempotent upsert (catalogRecipeId is _id), so if the
# pass reported failures, just replay them — the already-indexed docs are harmlessly overwritten.
if [[ -s "$FAILED_IDS_FILE" ]]; then
  COUNT=$(wc -l < "$FAILED_IDS_FILE" | tr -d ' ')
  echo ""
  echo "🩹 $COUNT id(s) failed the first pass; backfilling them (idempotent upsert)..."
  BACKFILL_LOG="$LOG_DIR/oracle-reindex-backfill-$TIMESTAMP.log"
  set +e
  AWS_REGION="$AWS_REGION" \
  COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
  OPENSEARCH_AUTH="basic" \
  OPENSEARCH_ENDPOINT="$OPENSEARCH_ENDPOINT" \
  OPENSEARCH_USERNAME="$OPENSEARCH_USERNAME" \
  OPENSEARCH_PASSWORD="$OPENSEARCH_PASSWORD" \
  OPENSEARCH_TLS_VERIFY="$TLS_VERIFY" \
  OPENSEARCH_SIGNING_SERVICE="es" \
  java -Xmx3g -jar "$JAR" \
    --server.port=0 \
    --catalog.search.backend=opensearch \
    --opensearch.knn.quantization="$QUANTIZATION" \
    --catalog.reindex.enabled=true \
    --catalog.reindex.backfill=true \
    --catalog.reindex.backfill-ids-file="$FAILED_IDS_FILE" \
    --catalog.reindex.batch-size=500 \
    --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
    2>&1 | tee "$BACKFILL_LOG"
  set -e
  grep "Backfill complete" "$BACKFILL_LOG" | tail -1 || true
fi

# ── Verify completeness (index _count vs DynamoDB count) ─────────────────────
echo ""
echo "🔎 Verifying index completeness (OpenSearch _count vs DynamoDB count)..."
set +e
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
OPENSEARCH_AUTH="basic" \
OPENSEARCH_ENDPOINT="$OPENSEARCH_ENDPOINT" \
OPENSEARCH_USERNAME="$OPENSEARCH_USERNAME" \
OPENSEARCH_PASSWORD="$OPENSEARCH_PASSWORD" \
OPENSEARCH_TLS_VERIFY="$TLS_VERIFY" \
OPENSEARCH_SIGNING_SERVICE="es" \
java -Xmx3g -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --catalog.reindex.enabled=true \
  --catalog.reindex.verify-count=true \
  --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" 2>&1 | tee -a "$LOG_FILE" | grep -E "Verify"
VERIFY_EXIT=${pipestatus[1]}
set -e

echo ""
if [[ $VERIFY_EXIT -eq 0 ]]; then
  echo "✅ Catalog fully indexed and VERIFIED complete at $(date)."
else
  echo "❌ Verification FAILED at $(date) — the index is short. See $LOG_FILE."
  echo "   Re-run this script (idempotent upsert) or the backfill to close the gap."
  exit 1
fi
echo "   Reindex log: $LOG_FILE"
exit 0
