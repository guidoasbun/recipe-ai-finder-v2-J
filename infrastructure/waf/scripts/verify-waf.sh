#!/usr/bin/env bash
#
# Post-deployment WAF verification script
# Verifies that WAF resources are properly deployed and configured.
#
# Usage: ./verify-waf.sh <environment> [<app-url>]
#   environment: dev or prod
#   app-url: optional base URL for health check (default: https://recipe-ai-finder.com)
#
# Prerequisites:
#   - AWS CLI configured with appropriate credentials
#   - curl installed
#   - jq installed

set -euo pipefail

ENV="${1:?Usage: $0 <environment> [app-url]}"
APP_URL="${2:-https://recipe-ai-finder.com}"
PROJECT_NAME="recipe-ai"
PASSED=0
FAILED=0

pass() {
  PASSED=$((PASSED + 1))
}

fail() {
  FAILED=$((FAILED + 1))
}

check() {
  local description="$1"
  shift
  echo -n "  Checking: ${description}... "
  if "$@" > /dev/null 2>&1; then
    echo "PASS"
    pass
  else
    echo "FAIL"
    fail
  fi
}

echo "============================================"
echo " WAF Post-Deployment Verification"
echo " Environment: ${ENV}"
echo "============================================"
echo ""

# 1. Verify Web ACL exists
echo "[1/4] Web ACL Existence"
WEB_ACL_NAME="${PROJECT_NAME}-${ENV}-web-acl"
WEB_ACL_INFO=$(aws wafv2 list-web-acls --scope REGIONAL --query "WebACLs[?Name=='${WEB_ACL_NAME}']" --output json 2>/dev/null || echo "[]")
WEB_ACL_ID=$(echo "$WEB_ACL_INFO" | jq -r '.[0].Id // empty')

if [ -n "$WEB_ACL_ID" ]; then
  echo "  PASS - Web ACL '${WEB_ACL_NAME}' exists (ID: ${WEB_ACL_ID})"
  pass
else
  echo "  FAIL - Web ACL '${WEB_ACL_NAME}' NOT found"
  fail
fi
echo ""

# 2. Verify ALB Association
echo "[2/4] ALB Association"
ALB_NAME="${PROJECT_NAME}-${ENV}-alb"
ALB_ARN=$(aws elbv2 describe-load-balancers \
  --names "$ALB_NAME" \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text 2>/dev/null || echo "")

if [ -n "$ALB_ARN" ] && [ "$ALB_ARN" != "None" ]; then
  ASSOCIATED_ACL=$(aws wafv2 get-web-acl-for-resource \
    --resource-arn "$ALB_ARN" \
    --query 'WebACL.Name' \
    --output text 2>/dev/null || echo "")
  if [ "$ASSOCIATED_ACL" = "$WEB_ACL_NAME" ]; then
    echo "  PASS - Web ACL associated with ALB '${ALB_NAME}'"
    pass
  else
    echo "  FAIL - Web ACL NOT associated with ALB (got: '${ASSOCIATED_ACL}')"
    fail
  fi
else
  echo "  FAIL - ALB '${ALB_NAME}' not found"
  fail
fi
echo ""

# 3. Verify Health Endpoint
echo "[3/4] Health Endpoint Accessibility"
HEALTH_URL="${APP_URL}/api/health"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$HEALTH_URL" 2>/dev/null || echo "000")
if [ "$HTTP_STATUS" = "200" ]; then
  echo "  PASS - Health endpoint returned HTTP 200"
  pass
else
  echo "  FAIL - Health endpoint returned HTTP ${HTTP_STATUS} (expected 200)"
  fail
fi
echo ""

# 4. Verify S3 Logging Bucket
echo "[4/4] S3 Logging Bucket"
BUCKET_NAME="aws-waf-logs-${PROJECT_NAME}-${ENV}"
check "Bucket '${BUCKET_NAME}' exists" aws s3api head-bucket --bucket "$BUCKET_NAME"

LIFECYCLE=$(aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET_NAME" 2>/dev/null || echo "")
if echo "$LIFECYCLE" | jq -e '.Rules[] | select(.Expiration.Days == 90)' > /dev/null 2>&1; then
  echo "  PASS - Lifecycle policy with 90-day expiration configured"
  pass
else
  echo "  FAIL - Lifecycle policy with 90-day expiration NOT found"
  fail
fi
echo ""

# Summary
echo "============================================"
echo " Results: ${PASSED} passed, ${FAILED} failed"
echo "============================================"

if [ "$FAILED" -gt 0 ]; then
  exit 1
fi
exit 0
