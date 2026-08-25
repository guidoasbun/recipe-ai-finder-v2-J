aws_region   = "us-east-1"
project_name = "recipe-ai"
environment  = "prod"

domain_name = "recipe-ai-finder.com"

cognito_google_client_id_ssm     = "/recipe-ai/prod/google-client-id"
cognito_google_client_secret_ssm = "/recipe-ai/prod/google-client-secret"

# Run: aws secretsmanager create-secret --name recipe-ai-prod-stability-api-key --secret-string "sk-..."
# Then paste the ARN printed by that command here
stability_api_key_arn = "arn:aws:secretsmanager:us-east-1:412381751532:secret:recipe-ai-prod-stability-api-key"
openai_api_key_arn    = "arn:aws:secretsmanager:us-east-1:412381751532:secret:recipe-ai-prod-openai-api-key"

# --- WAF Configuration ---
waf_allowed_ips                      = []
waf_allowed_ips_v6                   = []
waf_blocked_ips                      = []
waf_blocked_ips_v6                   = []
waf_geo_block_countries              = []
waf_rate_limit_global                = 2000
waf_rate_limit_recipe_gen            = 100
waf_rate_limit_image_upload          = 100
waf_rate_limit_auth                  = 100
waf_alarm_sns_topic_arn              = ""
waf_blocked_requests_alarm_threshold = 1000
waf_budget_limit_amount              = "50"
waf_budget_notification_email        = ""
