#!/usr/bin/env zsh
#
# Rebuild the full catalog index in OpenSearch, end to end and self-healing.
#
# What it does:
#   1. Checks prerequisites (AWS creds, JAR built).
#   2. PASS 1 — recreates the index with the fp16 mapping (drops the old one) and bulk-indexes
#      all ~2.23M recipes from DynamoDB (concurrency 4, retry-on-throttle). Vectors are read from
#      DynamoDB (no Bedrock). Any recipe that still fails after retries has its catalogRecipeId
#      written to a failed-ids file.
#   3. PASSES 2..N — automatically backfills ONLY the failed ids (single-threaded, patient
#      backoff, via findById — the serverless-safe path), looping until 0 failed or no progress.
#      Backfilling failed ids adds no duplicates because those ids are, by definition, not yet in
#      the index (serverless auto-generates _id, so we never re-index something already present).
#
# Net result: one command rebuilds everything AND closes its own gaps, ending only when the index
# is complete (or it gives up after MAX_BACKFILL_PASSES and tells you what's left).
#
# How to run (from the project root):
#   ./scripts/run-full-catalog-reindex.sh
#
# Self-caffeinates so macOS won't sleep mid-run. Runtime: ~5-6 hours for pass 1, then minutes per
# backfill pass. Keep the Mac plugged in.
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
LOG_FILE="$LOG_DIR/full-catalog-reindex-$TIMESTAMP.log"
# Any recipe that can't be indexed after retries has its catalogRecipeId appended here, so a
# targeted backfill can replay exactly those (see scripts/run-catalog-backfill.sh).
FAILED_IDS_FILE="$LOG_DIR/reindex-failed-ids-$TIMESTAMP.txt"

# ── Configuration ────────────────────────────────────────────────────────────
AWS_REGION="us-east-1"
COGNITO_ISSUER_URI="https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5je5ZFisi"
OPENSEARCH_ENDPOINT="https://o2dmi7wacuk8u8y9pbm6.us-east-1.aoss.amazonaws.com"
CATALOG_FULL_TABLE="recipe-ai-dev-catalog-full"
QUANTIZATION="fp16"
BATCH_SIZE=1000
# Concurrency 4 (not 8): 8 triggered OpenSearch Serverless indexing throttling ("[throttled]")
# at ~1.06M docs on the prior run. The run is often DynamoDB-scan-bound, so 4 costs little
# throughput while staying under the serverless indexing OCU ceiling and avoiding throttle storms.
CONCURRENCY=4

# ── Prerequisite checks ─────────────────────────────────────────────────────
echo "🔍 Checking prerequisites..."
if ! aws sts get-caller-identity &>/dev/null; then
  echo "❌ AWS credentials not configured. Run 'aws configure' first."
  exit 1
fi
echo "  ✅ AWS credentials OK"

echo "  🔨 Building backend JAR (fresh, so it matches current source)..."
(cd "$BACKEND_DIR" && ./mvnw -q package -DskipTests)
if [[ ! -f "$JAR" ]]; then
  echo "❌ Build failed. Fix errors and re-run."
  exit 1
fi
echo "  ✅ JAR built (fresh)"

mkdir -p "$LOG_DIR"
echo ""
echo "📋 Reindexing $CATALOG_FULL_TABLE → OpenSearch (quantization=$QUANTIZATION)"
echo "   Recreates the index with the fp16 mapping, then bulk-indexes ~2.23M recipes."
echo "   Expect ~5-6 hours. Logging to: $LOG_FILE"
echo "   Watch progress: grep 'Reindex progress' $LOG_FILE | tail -3"
echo ""
echo "Starting in 5 seconds... (Ctrl+C to abort)"
sleep 5

echo "🚀 Starting reindex at $(date)"
echo ""

# ── Pass 1: full recreate reindex ────────────────────────────────────────────
# Rebuilds the index from scratch (drop + recreate) and captures any recipe that could not be
# indexed after retries into FAILED_IDS_FILE. The runner exits non-zero if anything failed
# (completeness guard); we handle that below with automatic backfill passes, so don't let
# set -e abort the script here.
set +e
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -Xmx3g -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
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

# ── Passes 2..N: automatic backfill of failed ids ────────────────────────────
# The full reindex recreates the index, so it is not re-runnable without duplicating (serverless
# auto-generates _id). But the failed ids are safe to re-index: they are, by definition, NOT yet
# in the index, so backfilling them adds no duplicates. Loop until the failed set is empty or a
# pass makes no progress.
CURRENT_FAILED="$FAILED_IDS_FILE"
ATTEMPT=1
MAX_BACKFILL_PASSES=6

while [[ -s "$CURRENT_FAILED" ]]; do
  COUNT=$(wc -l < "$CURRENT_FAILED" | tr -d ' ')
  echo ""
  echo "🩹 Backfill pass $ATTEMPT: $COUNT missing id(s) from $CURRENT_FAILED"
  if [[ $ATTEMPT -gt $MAX_BACKFILL_PASSES ]]; then
    echo "❌ Still $COUNT failed after $MAX_BACKFILL_PASSES backfill passes. Stopping."
    echo "   Investigate: $CURRENT_FAILED"
    exit 1
  fi

  NEXT_FAILED="$LOG_DIR/reindex-backfill-failed-$TIMESTAMP-pass$ATTEMPT.txt"
  BACKFILL_LOG="$LOG_DIR/reindex-backfill-$TIMESTAMP-pass$ATTEMPT.log"

  set +e
  AWS_REGION="$AWS_REGION" \
  COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
  java -Xmx3g -jar "$JAR" \
    --server.port=0 \
    --catalog.search.backend=opensearch \
    --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
    --opensearch.knn.quantization="$QUANTIZATION" \
    --catalog.reindex.enabled=true \
    --catalog.reindex.backfill=true \
    --catalog.reindex.backfill-ids-file="$CURRENT_FAILED" \
    --catalog.reindex.failed-ids-file="$NEXT_FAILED" \
    --catalog.reindex.batch-size=500 \
    --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
    2>&1 | tee "$BACKFILL_LOG"
  BF_EXIT=${pipestatus[1]}
  set -e

  grep "Backfill complete" "$BACKFILL_LOG" | tail -1 || true

  # Guard against an infinite loop: if this pass didn't shrink the failed set, stop.
  if [[ -s "$NEXT_FAILED" ]]; then
    NEXT_COUNT=$(wc -l < "$NEXT_FAILED" | tr -d ' ')
    if [[ "$NEXT_COUNT" -ge "$COUNT" ]]; then
      echo "❌ Backfill pass $ATTEMPT made no progress ($COUNT -> $NEXT_COUNT). Stopping."
      echo "   Remaining: $NEXT_FAILED"
      exit 1
    fi
  fi

  CURRENT_FAILED="$NEXT_FAILED"
  ATTEMPT=$((ATTEMPT + 1))
done

echo ""
echo "🔎 Verifying index completeness (OpenSearch _count vs DynamoDB count)..."
set +e
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -Xmx3g -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
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
  echo "   Run the reconciliation backfill: ./scripts/run-catalog-backfill.sh"
  exit 1
fi
echo "   Reindex log:  $LOG_FILE"
exit 0
