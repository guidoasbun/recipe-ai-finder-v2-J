variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type        = string
  description = "AWS region, used for the WAF alarm's Region dimension in the dashboard and for building metric widgets."
}

# ── Opt-in + notifications ─────────────────────────────────────────────────────

variable "enable_monitoring" {
  type        = bool
  default     = false
  description = "Provision the CloudWatch monitoring suite (SNS topic, alarms, dashboard, budget). Off by default so the standard deployment provisions nothing new and incurs no extra cost (mirrors enable_opensearch)."
}

variable "notification_email" {
  type        = string
  default     = ""
  description = "Email for alarm notifications AND the CloudWatch cost budget. Required when enable_monitoring=true (the module fails fast otherwise) so alarms can actually notify. Subscription confirmation is a one-time manual click in the email AWS sends."
}

variable "budget_limit_amount" {
  type        = string
  default     = "20"
  description = "Monthly budget (USD) scoped to CloudWatch [+X-Ray]. Design §9 estimates ~$5–15/mo; alert at $20."
}

# ── Signal sources (wired from the other modules' outputs) ─────────────────────

variable "ecs_cluster_name" {
  type        = string
  description = "ECS cluster name (ClusterName dimension for AWS/ECS alarms)."
}

variable "ecs_backend_service_name" {
  type        = string
  description = "Backend ECS service name (ServiceName dimension for AWS/ECS alarms)."
}

variable "alb_arn_suffix" {
  type        = string
  description = "ALB ARN suffix (LoadBalancer dimension for AWS/ApplicationELB metrics)."
}

variable "backend_tg_arn_suffix" {
  type        = string
  description = "Backend target-group ARN suffix (TargetGroup dimension for per-target ALB metrics)."
}

variable "dynamodb_table_names" {
  type        = list(string)
  description = "DynamoDB table names to alarm on for throttling (users/recipes/catalog/consent/audit-log, plus catalog-full when enabled). Empty strings are filtered out (catalog-full is empty when disabled)."
}

variable "nat_gateway_id" {
  type        = string
  description = "The single NAT gateway id (NatGatewayId dimension). This NAT is the whole app's egress chokepoint (Bedrock/DynamoDB/S3/Secrets/Cognito/image-providers/OpenSearch all leave through it)."
}

variable "waf_web_acl_name" {
  type        = string
  default     = ""
  description = "WAF WebACL name for the dashboard's allowed/blocked widget. Empty = omit the WAF widget."
}

# ── OpenSearch: OCI node (live) vs AWS Serverless (legacy) ─────────────────────

variable "enable_app_metrics" {
  type        = bool
  default     = false
  description = "Mirrors monitoring.metrics.enabled on the backend. When true, the dashboard renders the app-metric + OCI OpenSearch node-health widgets and the node-health alarms are created (they read app-pushed custom metrics)."
}

variable "enable_opensearch" {
  type        = bool
  default     = false
  description = "Legacy path: when the AWS OpenSearch Serverless collection is enabled, add its OCU/search-error alarms + dashboard widgets. The live backend is the OCI node (see enable_app_metrics), not this."
}

# ── Alarm thresholds (sensible defaults, overridable per env) ──────────────────

variable "ecs_cpu_threshold" {
  type        = number
  default     = 80
  description = "ECS service CPU utilization (%) alarm threshold."
}

variable "ecs_memory_threshold" {
  type        = number
  default     = 80
  description = "ECS service memory utilization (%) alarm threshold."
}

variable "alb_5xx_threshold" {
  type        = number
  default     = 10
  description = "ALB 5XX count (Sum over the period) alarm threshold."
}

variable "alb_p95_latency_threshold_seconds" {
  type        = number
  default     = 3
  description = "ALB target response time p95 (seconds) alarm threshold."
}

variable "nat_active_connections_threshold" {
  type        = number
  default     = 40000
  description = "NAT gateway ActiveConnectionCount alarm threshold (the hard limit is ~55k per destination; alarm before saturation)."
}

variable "alarm_period_seconds" {
  type        = number
  default     = 300
  description = "Default alarm period (seconds), matching the WAF alarm's 5-minute window."
}
