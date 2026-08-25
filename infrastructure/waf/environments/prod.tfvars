# Production environment WAF parameters

project_name = "recipe-ai"
environment  = "prod"

# IP lists (empty by default, add IPs as needed)
waf_allowed_ips         = []
waf_blocked_ips         = []
waf_geo_block_countries = []

# Rate limits (full production values)
waf_rate_limit_global       = 2000
waf_rate_limit_recipe_gen   = 100
waf_rate_limit_image_upload = 60
waf_rate_limit_auth         = 30

# Monitoring
waf_alarm_sns_topic_arn              = ""
waf_blocked_requests_alarm_threshold = 1000

# Budget
waf_budget_limit_amount       = "50"
waf_budget_notification_email = ""
