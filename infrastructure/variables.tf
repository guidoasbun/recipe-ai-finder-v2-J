variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "project_name" {
  type    = string
  default = "recipe-ai"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "domain_name" {
  type        = string
  description = "Your registered domain, e.g. example.com"
}

variable "cognito_google_client_id_ssm" {
  type        = string
  description = "SSM Parameter Store path for Google OAuth client ID"
}

variable "cognito_google_client_secret_ssm" {
  type        = string
  description = "SSM Parameter Store path for Google OAuth client secret"
}

variable "stability_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Stability AI API key"
}

variable "openai_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the OpenAI API key"
}

variable "google_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Google AI Studio API key"
}

# --- WAF Variables ---

variable "waf_allowed_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the WAF IP allow-list"
}

variable "waf_allowed_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the WAF IP allow-list"
}

variable "waf_blocked_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the WAF IP block-list"
}

variable "waf_blocked_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the WAF IP block-list"
}

variable "waf_geo_block_countries" {
  type        = list(string)
  default     = []
  description = "ISO 3166-1 alpha-2 country codes to block"
}

variable "waf_rate_limit_global" {
  type        = number
  default     = 2000
  description = "Global requests per 5-min window per IP"
}

variable "waf_rate_limit_recipe_gen" {
  type        = number
  default     = 100
  description = "Recipe generation requests per 5-min window per IP"
}

variable "waf_rate_limit_image_upload" {
  type        = number
  default     = 60
  description = "Image upload requests per 5-min window per IP"
}

variable "waf_rate_limit_auth" {
  type        = number
  default     = 30
  description = "Auth endpoint requests per 5-min window per IP"
}

variable "waf_alarm_sns_topic_arn" {
  type        = string
  default     = ""
  description = "SNS topic ARN for WAF CloudWatch alarms (empty = no alarm actions)"
}

variable "waf_blocked_requests_alarm_threshold" {
  type        = number
  default     = 1000
  description = "Blocked requests in 5-min period to trigger alarm"
}

variable "waf_budget_limit_amount" {
  type        = string
  default     = "50"
  description = "Monthly WAF budget limit in USD"
}

variable "waf_budget_notification_email" {
  type        = string
  default     = ""
  description = "Email for budget alerts (empty = no budget resource created)"
}
