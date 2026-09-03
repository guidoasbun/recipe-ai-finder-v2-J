aws_region   = "us-east-1"
project_name = "recipe-ai"
environment  = "dev"

domain_name = "recipe-ai-finder.com"

cognito_google_client_id_ssm     = "/recipe-ai/dev/google-client-id"
cognito_google_client_secret_ssm = "/recipe-ai/dev/google-client-secret"

# Run: aws secretsmanager create-secret --name recipe-ai-dev-stability-api-key --secret-string "sk-..."
# Then paste the ARN printed by that command here
stability_api_key_arn = "arn:aws:secretsmanager:us-east-1:412381751532:secret:recipe-ai-dev-stability-api-key"
openai_api_key_arn    = "arn:aws:secretsmanager:us-east-1:412381751532:secret:recipe-ai-dev-openai-api-key"

# Run: aws secretsmanager create-secret --name recipe-ai-dev-google-api-key --secret-string "AIzaSy..."
# Then paste the ARN printed by that command here
google_api_key_arn = "arn:aws:secretsmanager:us-east-1:412381751532:secret:recipe-ai-dev-google-api-key-v5ciAl"

# --- OpenSearch catalog search (cutover) ---
# Enables the serverless VECTORSEARCH collection + the full 2.2M catalog table, and points the
# backend at OpenSearch. Quantization must match the index the reindex built (fp16 at 2.2M).
# NOTE: enable_opensearch=true REQUIRES opensearch_budget_notification_email (module precondition
# enforces the cost alarm). enable_batch_embedding keeps the (near-zero-cost) Bedrock batch S3
# buckets + IAM role so the full-load infra isn't torn down on this apply.
enable_opensearch                    = true
enable_catalog_full                  = true
enable_batch_embedding               = true
catalog_search_backend               = "opensearch"
opensearch_knn_quantization          = "fp16"
opensearch_knn_ef_search             = 100
opensearch_budget_notification_email = "guido@asbun.io"

# To re-grant ad-hoc CLI/local data access to the collection later (reindex/backfill/debug),
# add the principal ARN here and apply. Leave empty for least-privilege (ECS task role only).
# The principal also needs aoss:APIAccessAll on its IAM side.
# opensearch_admin_principals = ["arn:aws:iam::412381751532:user/rodrigo-cli"]

# --- WAF Configuration ---
waf_allowed_ips                      = []
waf_allowed_ips_v6                   = []
waf_blocked_ips                      = []
waf_blocked_ips_v6                   = []
waf_geo_block_countries              = []
waf_rate_limit_global                = 1000
waf_rate_limit_recipe_gen            = 100
waf_rate_limit_image_upload          = 100
waf_rate_limit_auth                  = 100
waf_alarm_sns_topic_arn              = ""
waf_blocked_requests_alarm_threshold = 500
waf_budget_limit_amount              = "25"
waf_budget_notification_email        = ""
