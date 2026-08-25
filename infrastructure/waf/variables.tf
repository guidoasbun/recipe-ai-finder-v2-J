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

variable "waf_allowed_ips" {
  type        = list(string)
  default     = []
  description = "CIDR ranges for the IP allow-list"
}

variable "waf_blocked_ips" {
  type        = list(string)
  default     = []
  description = "CIDR ranges for the IP block-list"
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
  description = "SNS topic ARN for CloudWatch alarms"
}

variable "waf_blocked_requests_alarm_threshold" {
  type        = number
  default     = 1000
  description = "Number of blocked requests in a 5-min period to trigger an alarm"
}

variable "waf_budget_limit_amount" {
  type        = string
  default     = "50"
  description = "Monthly WAF budget limit in USD"
}

variable "waf_budget_notification_email" {
  type        = string
  description = "Email address for budget alert notifications"
}
