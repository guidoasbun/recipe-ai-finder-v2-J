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

# --- OpenSearch catalog search ---
# DISABLED 2026-09-04: AWS OpenSearch Serverless kept ~6.5 OCU warm for the 2.2M vector index
# even when idle (~$240/mo forecast), far over the ~$15 budget. The collection was deleted and
# search moved off AWS. enable_opensearch=false ensures `terraform apply` does NOT recreate the
# (expensive) serverless collection. The backend runs the in-app fallback until the off-AWS
# OpenSearch (Oracle free tier) is stood up; then catalog_search_backend flips to "opensearch"
# with a non-AWS endpoint + basic auth (see documents/opensearch-implementation.md).
#
# enable_catalog_full stays TRUE: the full 2.2M table (with embeddings) is the source of truth for
# rebuilding the index anywhere — deleting it would force a ~17h re-embed. Storage only (~$7/mo).
enable_opensearch                    = false
enable_catalog_full                  = true
enable_batch_embedding               = false
catalog_search_backend               = "inapp"
opensearch_knn_quantization          = "fp16"
opensearch_knn_ef_search             = 100
opensearch_budget_notification_email = "guido@asbun.io"

# To re-grant ad-hoc CLI/local data access to the collection later (reindex/backfill/debug),
# add the principal ARN here and apply. Leave empty for least-privilege (ECS task role only).
# The principal also needs aoss:APIAccessAll on its IAM side.
# opensearch_admin_principals = ["arn:aws:iam::412381751532:user/rodrigo-cli"]

# --- Self-hosted OpenSearch on Oracle Cloud (replaces AWS OpenSearch Serverless) ---
# Non-secret values only (OCIDs / fingerprint / region / admin CIDR). The OpenSearch admin
# password lives in the gitignored infrastructure/oci-secrets.auto.tfvars. The OCI API private
# key is read from oci_private_key_path (never committed). See documents/RUNBOOK-oracle-opensearch.md.
enable_oci_opensearch = true
oci_tenancy_ocid      = "ocid1.tenancy.oc1..aaaaaaaacirtpm74wletcnptrkct4stvu3rdjzcda66ul4o2veabqtxueufa"
oci_user_ocid         = "ocid1.user.oc1..aaaaaaaavmdgwndvsxuimimhof27g4f4ygmxe5f2nitbvk2vnu4rgebxp5sa"
oci_fingerprint       = "69:59:91:1b:1a:e7:38:53:fe:60:33:d9:66:83:1e:d4"
oci_region            = "us-sanjose-1"
oci_private_key_path  = "/Users/rodrigo/Oracle/guido@asbun.io-2026-09-04T19_21_11.946Z.pem"
oci_admin_cidr        = "70.95.245.8/32"

# Shape: 2 OCPU / 24 GB (PAYG, ~$13/mo) → fp16 quantization at reindex time.
# For the free $0 path instead, set oci_opensearch_memory_gb = 12 and reindex with QUANTIZATION=byte.
oci_opensearch_ocpus     = 2
oci_opensearch_memory_gb = 24

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
