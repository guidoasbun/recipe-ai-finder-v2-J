variable "project_name" {
  type        = string
  description = "Project name prefix for resource naming"
}

variable "environment" {
  type        = string
  description = "Environment (dev/prod)"
}

variable "alb_arn" {
  type        = string
  description = "ARN of the ALB to associate with the Web ACL"
}

variable "allowed_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the IP allow-list"
}

variable "allowed_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the IP allow-list"
}

variable "blocked_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the IP block-list"
}

variable "blocked_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the IP block-list"
}

variable "geo_block_countries" {
  type        = list(string)
  default     = []
  description = "ISO 3166-1 alpha-2 country codes to block"
}

variable "rate_limit_global" {
  type        = number
  default     = 2000
  description = "Global requests per 5-min window per IP"
}

variable "rate_limit_recipe_gen" {
  type        = number
  default     = 100
  description = "Recipe generation requests per 5-min window per IP"
}

variable "rate_limit_image_upload" {
  type        = number
  default     = 60
  description = "Image upload requests per 5-min window per IP"
}

variable "rate_limit_auth" {
  type        = number
  default     = 30
  description = "Auth endpoint requests per 5-min window per IP"
}

variable "waf_log_bucket_name" {
  type        = string
  description = "S3 bucket name for WAF logs"
}

variable "alarm_sns_topic_arn" {
  type        = string
  description = "SNS topic ARN for CloudWatch alarms"
}

variable "blocked_requests_alarm_threshold" {
  type        = number
  default     = 1000
  description = "Number of blocked requests in a 5-min period to trigger an alarm"
}

variable "budget_limit_amount" {
  type        = string
  default     = "50"
  description = "Monthly WAF budget limit in USD"
}

variable "budget_notification_email" {
  type        = string
  description = "Email address for budget alert notifications"
}
