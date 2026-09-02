#!/usr/bin/env zsh
#
# Backfill only the recipes MISSING from the OpenSearch catalog index — no full reindex.
#
# Use this after a full reindex that finished with some failed items (e.g. serverless indexing
# throttling dropped a few thousand). It indexes only what's missing, single-threaded with long,
# patient backoff, so a small replay does not re-trigger the throttling that dropped them.
#
# It NEVER recreates the index, so the ~2.2M already indexed are left untouched.
#
# How to run (from the project root):
#   # 1) From a failed-ids file produced by the reindex (fast, precise):
#   ./scripts/run-catalog-backfill.sh logs/reindex-failed-ids-YYYYMMDD-HHMMSS.txt
#
#   # 2) Reconcile automatically (no ids file): pulls every indexed catalogRecipeId from
#   #    OpenSearch and diffs against DynamoDB, then indexes the difference. Use this when there
#   #    is no failed-ids file (e.g. the failures came from an older run that didn't capture ids).
#   ./scripts/run-catalog-backfill.sh
#
# Self-caffeinates. Runtime: minutes for a few thousand missing (reconcile adds a few minutes to
# pull the indexed ids first). Keep the Mac plugged in.
#
set -euo pipefail

# Optional first arg: a file of catalogRecipeIds (one per line) to backfill.
IDS_FILE="${1:-}"

# Self-caffeinate (re-exec under caffeinate once), preserving the ids-file arg.
if [[ -z "${BACKFILL_CAFFEINATED:-}" ]] && command -v caffeinate >/dev/null 2>&1; then
  echo "☕ Re-launching under caffeinate to keep the Mac awake..."
  export BACKFILL_CAFFEINATED=1
  exec caffeinate -ims "$0" "$@"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BACKEND_DIR="$PROJECT_DIR/backend"
JAR="$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="$LOG_DIR/catalog-backfill-$TIMESTAMP.log"
# Anything still failing after the patient retries is re-captured here for another pass.
FAILED_IDS_FILE="$LOG_DIR/backfill-failed-ids-$TIMESTAMP.txt"

# ── Configuration ────────────────────────────────────────────────────────────
AWS_REGION="us-east-1"
COGNITO_ISSUER_URI="https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5je5ZFisi"
OPENSEARCH_ENDPOINT="https://o2dmi7wacuk8u8y9pbm6.us-east-1.aoss.amazonaws.com"
CATALOG_FULL_TABLE="recipe-ai-dev-catalog-full"
QUANTIZATION="fp16"
BATCH_SIZE=500

# ── Prerequisite checks ─────────────────────────────────────────────────────
echo "🔍 Checking prerequisites..."
if ! aws sts get-caller-identity &>/dev/null; then
  echo "❌ AWS credentials not configured. Run 'aws configure' first."
  exit 1
fi
echo "  ✅ AWS credentials OK"

if [[ -n "$IDS_FILE" ]]; then
  if [[ ! -f "$IDS_FILE" ]]; then
    echo "❌ Ids file not found: $IDS_FILE"
    exit 1
  fi
  echo "  ✅ Using ids file: $IDS_FILE ($(wc -l < "$IDS_FILE" | tr -d ' ') id(s))"
else
  echo "  ℹ️  No ids file given — will reconcile against the index (pull indexed ids, diff vs DynamoDB)."
fi

echo "  🔨 Building backend JAR (fresh, so it matches current source)..."
(cd "$BACKEND_DIR" && ./mvnw -q package -DskipTests)
if [[ ! -f "$JAR" ]]; then
  echo "❌ Build failed. Fix errors and re-run."
  exit 1
fi
echo "  ✅ JAR built (fresh)"

mkdir -p "$LOG_DIR"
echo ""
echo "📋 Backfilling missing recipes → OpenSearch (quantization=$QUANTIZATION), single-threaded."
echo "   Index is NOT recreated; only missing recipes are indexed."
echo "   Logging to: $LOG_FILE"
echo "   Watch progress: grep 'Backfill' $LOG_FILE | tail -5"
echo ""
echo "Starting in 5 seconds... (Ctrl+C to abort)"
sleep 5

echo "🚀 Starting backfill at $(date)"
echo ""

# Reconciliation (no ids file) holds every indexed catalogRecipeId (~2.2M short strings) in a
# HashSet while it diffs against DynamoDB. Give the JVM room so that can't OOM. Harmless for the
# small ids-file path too.
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -Xmx2g -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
  --opensearch.knn.quantization="$QUANTIZATION" \
  --catalog.reindex.enabled=true \
  --catalog.reindex.backfill=true \
  --catalog.reindex.backfill-ids-file="$IDS_FILE" \
  --catalog.reindex.failed-ids-file="$FAILED_IDS_FILE" \
  --catalog.reindex.batch-size=$BATCH_SIZE \
  --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
  2>&1 | tee "$LOG_FILE"

EXIT_CODE=${pipestatus[1]}
echo ""
if [[ $EXIT_CODE -eq 0 ]]; then
  echo "✅ Backfill completed at $(date)"
  echo "   Summary: grep 'Backfill complete' $LOG_FILE"
else
  echo "⚠️  Backfill exited with code $EXIT_CODE at $(date). Check: $LOG_FILE"
  if [[ -s "$FAILED_IDS_FILE" ]]; then
    echo "   Some items still failed: $FAILED_IDS_FILE ($(wc -l < "$FAILED_IDS_FILE" | tr -d ' ') id(s))."
    echo "   Re-run against just those: ./scripts/run-catalog-backfill.sh \"$FAILED_IDS_FILE\""
  fi
fi
exit $EXIT_CODE
