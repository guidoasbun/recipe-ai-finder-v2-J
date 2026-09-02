#!/usr/bin/env zsh
#
# Fast yes/no: can we create a point-in-time (PIT) + read a page on the OpenSearch collection?
# The reconciliation backfill (scripts/run-catalog-backfill.sh with no ids file) depends on PIT.
# This probe scans NOTHING — it just tries PIT create + one search_after page, then exits.
#
#   ./scripts/probe-pit-support.sh
#
# SUCCEEDS -> "PIT probe SUCCEEDED" in the output; the reconciliation backfill is safe to run.
# FAILS    -> clear error; use the clean recreate reindex instead (scripts/run-full-catalog-reindex.sh).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BACKEND_DIR="$PROJECT_DIR/backend"
JAR="$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT.jar"

AWS_REGION="us-east-1"
COGNITO_ISSUER_URI="https://cognito-idp.us-east-1.amazonaws.com/us-east-1_5je5ZFisi"
OPENSEARCH_ENDPOINT="https://o2dmi7wacuk8u8y9pbm6.us-east-1.aoss.amazonaws.com"

if ! aws sts get-caller-identity &>/dev/null; then
  echo "❌ AWS credentials not configured."
  exit 1
fi

echo "🔨 Building backend JAR (fresh)..."
(cd "$BACKEND_DIR" && ./mvnw -q package -DskipTests)
[[ -f "$JAR" ]] || { echo "❌ Build failed."; exit 1; }

echo "🔬 Probing PIT support (no scan)..."
AWS_REGION="$AWS_REGION" \
COGNITO_ISSUER_URI="$COGNITO_ISSUER_URI" \
java -jar "$JAR" \
  --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint="$OPENSEARCH_ENDPOINT" \
  --catalog.reindex.enabled=true \
  --catalog.reindex.pit-probe=true 2>&1 | grep -E "PIT (probe|created)|ERROR|IllegalState|Could not create"
