#!/usr/bin/env zsh
#
# Reindex the full catalog (DynamoDB catalog-full) into OpenSearch with fp16 quantization.
#
# What it does:
#   1. Checks prerequisites (AWS creds, JAR built, OpenSearch endpoint reachable).
#   2. Recreates the OpenSearch index with the fp16 mapping (drops the old one), then bulk-indexes
#      all ~2.23M recipes from DynamoDB. No Bedrock — vectors are read from DynamoDB.
#   3. Logs to a timestamped file. Parallel bulk writes (concurrency) for throughput.
#
# How to run (from the project root):
#   ./scripts/run-full-catalog-reindex.sh
#
# Self-caffeinates so macOS won't sleep mid-run. Runtime: ~5-6 hours (~100 docs/sec).
# Safe to re-run: recreate-index rebuilds from scratch, so a rerun is clean (not incremental).
# Keep the Mac plugged in.
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

AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
  --opensearch.knn.quantization="$QUANTIZATION" \
  --catalog.reindex.enabled=true \
  --catalog.reindex.recreate-index=true \
  --catalog.reindex.batch-size=$BATCH_SIZE \
  --catalog.reindex.concurrency=$CONCURRENCY \
  --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
  2>&1 | tee "$LOG_FILE"

EXIT_CODE=${pipestatus[1]}
echo ""
if [[ $EXIT_CODE -eq 0 ]]; then
  echo "✅ Reindex completed at $(date)"
  echo "   Check the summary: grep 'Reindex complete' $LOG_FILE"
else
  echo "⚠️  Reindex exited with code $EXIT_CODE at $(date). Check: $LOG_FILE"
  echo "   Re-running is safe (recreate-index rebuilds from scratch)."
fi
exit $EXIT_CODE
