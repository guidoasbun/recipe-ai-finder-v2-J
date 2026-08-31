#!/usr/bin/env zsh
#
# Full 2.2M RecipeNLG catalog ingestion via Bedrock Batch Inference.
#
# What it does:
#   1. Checks prerequisites (AWS creds, JAR built, dataset file, DynamoDB table).
#   2. Runs the batch ingestion: parses RecipeNLG → batch-embeds via Bedrock → persists to
#      the catalog-full DynamoDB table. All ~23 batch jobs run sequentially, unattended.
#   3. Logs everything to a timestamped file so you can check progress or come back later.
#
# How to run (from the project root):
#   ./scripts/run-full-catalog-ingest.sh
#
# The script AUTOMATICALLY re-launches itself under `caffeinate` so macOS will not sleep
# mid-run — you do NOT need to prefix it yourself. (Prefixing with caffeinate is harmless too.)
# Runtime: expect several hours (each 100K-record batch job takes ~30-60 min).
# Cost: ~$3-6 one-time for Bedrock batch embeddings + ~$3-5 DynamoDB writes.
#
# Safe to re-run: idempotent (skip-if-already-embedded), so if it dies, just re-run.
# The script exits non-zero if the ingestion reports failures.
#
set -euo pipefail

# Self-caffeinate: if not already running under caffeinate, re-exec under it so the Mac stays
# awake for the whole run. The guard env var prevents an infinite re-exec loop.
if [[ -z "${INGEST_CAFFEINATED:-}" ]] && command -v caffeinate >/dev/null 2>&1; then
  echo "☕ Re-launching under caffeinate to keep the Mac awake for the full run..."
  export INGEST_CAFFEINATED=1
  # -i prevent idle sleep, -m prevent disk sleep, -s prevent system sleep (on AC power)
  exec caffeinate -ims "$0" "$@"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BACKEND_DIR="$PROJECT_DIR/backend"
JAR="$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/logs"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="$LOG_DIR/full-catalog-ingest-$TIMESTAMP.log"

# ── Configuration ────────────────────────────────────────────────────────────
AWS_REGION="us-east-1"
COGNITO_ISSUER_URI="https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5je5ZFisi"
CATALOG_FULL_TABLE="recipe-ai-dev-catalog-full"
BATCH_INPUT_BUCKET="recipe-ai-dev-batch-embed-input-412381751532"
BATCH_OUTPUT_BUCKET="recipe-ai-dev-batch-embed-output-412381751532"
BATCH_ROLE_ARN="arn:aws:iam::412381751532:role/recipe-ai-dev-batch-embed-role"
# Absolute path so it resolves regardless of the java process working directory.
RECIPENLG_FILE="$BACKEND_DIR/data/recipeNGL/RecipeNLG_dataset.csv"
# 0 = full set (all ~2.23M records, no cap)
MAX_RECORDS=0
# Poll Bedrock job status every 60 seconds
POLL_SECONDS=60
# Records per in-memory chunk (each chunk → one Bedrock batch job of ≤100K)
BATCH_CHUNK_SIZE=100000

# ── Prerequisite checks ─────────────────────────────────────────────────────
echo "🔍 Checking prerequisites..."

if ! aws sts get-caller-identity &>/dev/null; then
  echo "❌ AWS credentials not configured. Run 'aws configure' first."
  exit 1
fi
echo "  ✅ AWS credentials OK"

if [[ ! -f "$JAR" ]]; then
  echo "  ⚠️  JAR not found at $JAR — building..."
  (cd "$BACKEND_DIR" && ./mvnw -q package -DskipTests)
  if [[ ! -f "$JAR" ]]; then
    echo "❌ Build failed. Fix errors and re-run."
    exit 1
  fi
fi
echo "  ✅ JAR built"

if [[ ! -f "$RECIPENLG_FILE" ]]; then
  echo "❌ RecipeNLG dataset not found at $RECIPENLG_FILE"
  exit 1
fi
echo "  ✅ RecipeNLG dataset found"

TABLE_STATUS=$(aws dynamodb describe-table --table-name "$CATALOG_FULL_TABLE" \
  --query 'Table.TableStatus' --output text 2>/dev/null || echo "MISSING")
if [[ "$TABLE_STATUS" != "ACTIVE" ]]; then
  echo "❌ DynamoDB table $CATALOG_FULL_TABLE is $TABLE_STATUS (expected ACTIVE)."
  echo "   Run terraform apply with enable_catalog_full=true first."
  exit 1
fi
echo "  ✅ DynamoDB table $CATALOG_FULL_TABLE is ACTIVE"

# ── Prepare log directory ────────────────────────────────────────────────────
mkdir -p "$LOG_DIR"
echo ""
echo "📋 Ingesting full RecipeNLG dataset (~2.23M recipes) into $CATALOG_FULL_TABLE"
echo "   Bedrock batch jobs will run sequentially, ~23 jobs of ≤100K each."
echo "   Expect several hours. Logging to: $LOG_FILE"
echo "   Safe to re-run if interrupted (idempotent)."
echo ""
echo "Starting in 5 seconds... (Ctrl+C to abort)"
sleep 5

# ── Run the ingestion ────────────────────────────────────────────────────────
echo "🚀 Starting ingestion at $(date)"
echo "   Tail the log with: tail -f $LOG_FILE"
echo ""

AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -Xmx4g -jar "$JAR" \
  --server.port=0 \
  --catalog.ingest.enabled=true \
  --catalog.ingest.embedding-strategy=batch \
  --catalog.ingest.recipenlg-file="$RECIPENLG_FILE" \
  --catalog.ingest.recipenlg-max-records=$MAX_RECORDS \
  --catalog.ingest.recipenlg-skip-records=0 \
  --catalog.ingest.batch-chunk-size=$BATCH_CHUNK_SIZE \
  --dynamodb.catalog-full-table="$CATALOG_FULL_TABLE" \
  --bedrock.batch.input-bucket="$BATCH_INPUT_BUCKET" \
  --bedrock.batch.output-bucket="$BATCH_OUTPUT_BUCKET" \
  --bedrock.batch.role-arn="$BATCH_ROLE_ARN" \
  --bedrock.batch.poll-seconds=$POLL_SECONDS \
  2>&1 | tee "$LOG_FILE"

EXIT_CODE=${pipestatus[1]}

echo ""
if [[ $EXIT_CODE -eq 0 ]]; then
  echo "✅ Ingestion completed successfully at $(date)"
  echo "   Check the log for the summary: grep 'Batch ingestion complete' $LOG_FILE"
  echo ""
  echo "Next steps:"
  echo "  1. Verify: aws dynamodb describe-table --table-name $CATALOG_FULL_TABLE --query Table.ItemCount"
  echo "     (ItemCount updates ~every 6 hours; scan for a live count)"
  echo "  2. Reindex into OpenSearch (see RUNBOOK §3.4)"
else
  echo "⚠️  Ingestion exited with code $EXIT_CODE at $(date)"
  echo "   Check the log for errors: $LOG_FILE"
  echo "   Re-running is safe (idempotent — skips already-embedded recipes)."
fi

exit $EXIT_CODE
