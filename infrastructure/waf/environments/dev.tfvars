# Dev environment WAF parameters (50% of production thresholds)

project_name = "recipe-ai"
environment  = "dev"

# IP lists (empty by default, add IPs as needed)
waf_allowed_ips         = []
waf_allowed_ips_v6      = []
waf_blocked_ips         = []
waf_blocked_ips_v6      = []
waf_geo_block_countries = []

# Rate limits (50% of production values)
waf_rate_limit_global       = 1000
waf_rate_limit_recipe_gen   = 50
waf_rate_limit_image_upload = 30
waf_rate_limit_auth         = 15

# Monitoring
waf_alarm_sns_topic_arn              = ""
waf_blocked_requests_alarm_threshold = 500

# Budget
waf_budget_limit_amount       = "25"
waf_budget_notification_email = ""
