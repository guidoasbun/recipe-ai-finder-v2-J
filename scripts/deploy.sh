#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/deploy.sh dev
#        ./scripts/deploy.sh prod

ENVIRONMENT=${1:-dev}
AWS_REGION="us-east-1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$SCRIPT_DIR/../infrastructure"

echo "==> Fetching Terraform outputs for environment: $ENVIRONMENT"
cd "$INFRA_DIR"

BACKEND_ECR=$(terraform output -raw backend_ecr_url)
FRONTEND_ECR=$(terraform output -raw frontend_ecr_url)
BACKEND_SERVICE=$(terraform output -raw backend_service_name 2>/dev/null || echo "recipe-ai-${ENVIRONMENT}-backend")
FRONTEND_SERVICE=$(terraform output -raw frontend_service_name 2>/dev/null || echo "recipe-ai-${ENVIRONMENT}-frontend")
CLUSTER="recipe-ai-${ENVIRONMENT}-cluster"

echo "==> Logging into ECR"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin \
    "$(echo "$BACKEND_ECR" | cut -d/ -f1)"

echo "==> Building backend image (linux/arm64)"
cd "$SCRIPT_DIR/.."
docker buildx build \
  --platform linux/arm64 \
  --file docker/backend.Dockerfile \
  --tag "$BACKEND_ECR:latest" \
  --tag "$BACKEND_ECR:$(git rev-parse --short HEAD)" \
  --push \
  ./backend

echo "==> Building frontend image (linux/arm64)"
docker buildx build \
  --platform linux/arm64 \
  --file docker/frontend.Dockerfile \
  --tag "$FRONTEND_ECR:latest" \
  --tag "$FRONTEND_ECR:$(git rev-parse --short HEAD)" \
  --push \
  ./frontend

echo "==> Forcing new ECS deployments"
aws ecs update-service \
  --cluster "$CLUSTER" \
  --service "$BACKEND_SERVICE" \
  --force-new-deployment \
  --region "$AWS_REGION" \
  --output none

aws ecs update-service \
  --cluster "$CLUSTER" \
  --service "$FRONTEND_SERVICE" \
  --force-new-deployment \
  --region "$AWS_REGION" \
  --output none

echo "==> Deploy complete. ECS is pulling the new images."
echo "    Monitor progress: AWS Console → ECS → $CLUSTER"
