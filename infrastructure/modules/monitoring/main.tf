# CloudWatch Monitoring Suite
#
# One consolidated observability layer: SNS notification channel, native-service alarms (ECS,
# ALB, DynamoDB, Bedrock, NAT gateway), OpenSearch node-health alarms (OCI node, fed by
# app-pushed custom metrics) + legacy Serverless alarms, an image-gen log-metric filter, one
# dashboard, and a CloudWatch cost budget. Everything is gated by enable_monitoring so the
# default deployment provisions nothing (count = enable ? 1 : 0, mirroring modules/opensearch).

locals {
  enabled = var.enable_monitoring

  common_tags = {
    Environment = var.environment
    Project     = var.project_name
  }

  # catalog-full is passed as "" when disabled — drop empties so we don't alarm on a phantom table.
  dynamodb_tables = [for t in var.dynamodb_table_names : t if t != ""]

  alarm_actions = local.enabled ? [aws_sns_topic.alarms[0].arn] : []
}

data "aws_region" "current" {}

# ── Fail fast: notifications need an email (mirror the opensearch precondition) ─
resource "terraform_data" "require_email_when_enabled" {
  count = local.enabled ? 1 : 0
  lifecycle {
    precondition {
      condition     = var.notification_email != ""
      error_message = "enable_monitoring=true requires monitoring_notification_email (alarms must be able to notify)."
    }
  }
}

# ── SNS notification channel ───────────────────────────────────────────────────
resource "aws_sns_topic" "alarms" {
  count = local.enabled ? 1 : 0
  name  = "${var.project_name}-${var.environment}-alarms"
  tags = merge(local.common_tags, {
    Name = "${var.project_name}-${var.environment}-alarms"
  })
}

resource "aws_sns_topic_subscription" "email" {
  count     = local.enabled ? 1 : 0
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.notification_email
}

# ── ECS service alarms ─────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-ecs-cpu-high"
  alarm_description   = "Backend ECS service CPU utilization above ${var.ecs_cpu_threshold}%."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = var.alarm_period_seconds
  statistic           = "Average"
  threshold           = var.ecs_cpu_threshold
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_backend_service_name
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-ecs-cpu-high" })
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-ecs-memory-high"
  alarm_description   = "Backend ECS service memory utilization above ${var.ecs_memory_threshold}%."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "MemoryUtilization"
  namespace           = "AWS/ECS"
  period              = var.alarm_period_seconds
  statistic           = "Average"
  threshold           = var.ecs_memory_threshold
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_backend_service_name
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-ecs-memory-high" })
}

# ── ALB alarms ─────────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "alb_target_5xx" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-alb-target-5xx-high"
  alarm_description   = "Backend target 5XX responses above ${var.alb_5xx_threshold} in the period."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "HTTPCode_Target_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = var.alb_5xx_threshold
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.backend_tg_arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-alb-target-5xx-high" })
}

resource "aws_cloudwatch_metric_alarm" "alb_elb_5xx" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-alb-elb-5xx-high"
  alarm_description   = "ALB-generated 5XX responses above ${var.alb_5xx_threshold} in the period (backend unreachable / no healthy hosts)."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "HTTPCode_ELB_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = var.alb_5xx_threshold
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-alb-elb-5xx-high" })
}

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_hosts" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-alb-unhealthy-hosts"
  alarm_description   = "Backend target group has one or more unhealthy hosts."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "UnHealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = var.alarm_period_seconds
  statistic           = "Maximum"
  threshold           = 0
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.backend_tg_arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-alb-unhealthy-hosts" })
}

resource "aws_cloudwatch_metric_alarm" "alb_p95_latency" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-alb-latency-p95-high"
  alarm_description   = "Backend p95 target response time above ${var.alb_p95_latency_threshold_seconds}s."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "TargetResponseTime"
  namespace           = "AWS/ApplicationELB"
  period              = var.alarm_period_seconds
  extended_statistic  = "p95"
  threshold           = var.alb_p95_latency_threshold_seconds
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.backend_tg_arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-alb-latency-p95-high" })
}

# ── DynamoDB throttle alarms (one per table; on-demand tables throttle rather than error) ─
resource "aws_cloudwatch_metric_alarm" "ddb_throttle" {
  for_each            = local.enabled ? toset(local.dynamodb_tables) : toset([])
  alarm_name          = "${each.value}-throttle-high"
  alarm_description   = "DynamoDB throttled requests on ${each.value}."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ThrottledRequests"
  namespace           = "AWS/DynamoDB"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  dimensions = {
    TableName = each.value
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
  tags          = merge(local.common_tags, { Name = "${each.value}-throttle-high" })
}

# ── Bedrock invocation-error alarm ─────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "bedrock_errors" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-bedrock-errors-high"
  alarm_description   = "Bedrock server-side invocation errors detected."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "InvocationServerErrors"
  namespace           = "AWS/Bedrock"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-bedrock-errors-high" })
}

# ── NAT gateway alarms (the single egress chokepoint) ──────────────────────────
resource "aws_cloudwatch_metric_alarm" "nat_port_alloc_errors" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-nat-port-alloc-errors"
  alarm_description   = "NAT gateway failed to allocate a source port (port exhaustion) — outbound calls will fail."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ErrorPortAllocation"
  namespace           = "AWS/NATGateway"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  dimensions          = { NatGatewayId = var.nat_gateway_id }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-nat-port-alloc-errors" })
}

resource "aws_cloudwatch_metric_alarm" "nat_packets_dropped" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-nat-packets-dropped"
  alarm_description   = "NAT gateway dropped packets — possible saturation or a networking fault on the whole-app egress path."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "PacketsDropCount"
  namespace           = "AWS/NATGateway"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  dimensions          = { NatGatewayId = var.nat_gateway_id }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-nat-packets-dropped" })
}

resource "aws_cloudwatch_metric_alarm" "nat_active_connections" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-nat-active-connections-high"
  alarm_description   = "NAT gateway active connection count above ${var.nat_active_connections_threshold} (approaching the ~55k per-destination limit)."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ActiveConnectionCount"
  namespace           = "AWS/NATGateway"
  period              = var.alarm_period_seconds
  statistic           = "Maximum"
  threshold           = var.nat_active_connections_threshold
  dimensions          = { NatGatewayId = var.nat_gateway_id }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-nat-active-connections-high" })
}

# ── OpenSearch node health alarms (OCI node; fed by app-pushed custom metrics) ─
# These read metrics the backend health probe pushes to RecipeAiFinder/App (task 6). They only
# make sense when app metrics are enabled — otherwise the metrics never arrive.
resource "aws_cloudwatch_metric_alarm" "opensearch_node_down" {
  count               = local.enabled && var.enable_app_metrics ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-opensearch-node-down"
  alarm_description   = "The self-hosted OpenSearch node (Oracle Cloud) is unreachable (OpenSearchNodeUp < 1). Missing data is treated as breaching, since a node that cannot report is down."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "OpenSearchNodeUp"
  namespace           = "RecipeAiFinder/App"
  period              = 60
  statistic           = "Maximum"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-opensearch-node-down" })
}

resource "aws_cloudwatch_metric_alarm" "opensearch_cluster_red" {
  count               = local.enabled && var.enable_app_metrics ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-opensearch-cluster-red"
  alarm_description   = "OpenSearch cluster status is red (OpenSearchClusterStatus < 1; green=2/yellow=1/red=0)."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "OpenSearchClusterStatus"
  namespace           = "RecipeAiFinder/App"
  period              = 60
  statistic           = "Minimum"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-opensearch-cluster-red" })
}

# ── Legacy: AWS OpenSearch Serverless (only if it is ever re-enabled) ──────────
resource "aws_cloudwatch_metric_alarm" "opensearch_serverless_ocu" {
  count               = local.enabled && var.enable_opensearch ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-opensearch-serverless-ocu-high"
  alarm_description   = "AWS OpenSearch Serverless search OCU consumption is high (legacy path; the live backend is the OCI node)."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "SearchOCU"
  namespace           = "AWS/AOSS"
  period              = var.alarm_period_seconds
  statistic           = "Maximum"
  threshold           = 6
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-opensearch-serverless-ocu-high" })
}

# ── Log-derived metric: image-generation final failure (no app change required) ─
# Matches the AsyncImageService line: "Image generation failed after N attempts for recipe ...".
resource "aws_cloudwatch_log_metric_filter" "image_gen_failures" {
  count          = local.enabled ? 1 : 0
  name           = "${var.project_name}-${var.environment}-image-gen-final-failures"
  log_group_name = "/ecs/${var.project_name}-${var.environment}-backend"
  pattern        = "\"Image generation failed after\""

  metric_transformation {
    name          = "ImageGenFinalFailures"
    namespace     = "RecipeAiFinder/App"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "image_gen_failures" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-image-gen-final-failures"
  alarm_description   = "Image generation exhausted all retries for one or more recipes."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ImageGenFinalFailures"
  namespace           = "RecipeAiFinder/App"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-image-gen-final-failures" })
}

# ── Log-derived metric: Bedrock retry-exhaustion (no request-time app metric required) ─
# Matches the BedrockService line: "Bedrock generation failed after N attempts".
resource "aws_cloudwatch_log_metric_filter" "bedrock_retry_exhausted" {
  count          = local.enabled ? 1 : 0
  name           = "${var.project_name}-${var.environment}-bedrock-retry-exhausted"
  log_group_name = "/ecs/${var.project_name}-${var.environment}-backend"
  pattern        = "\"Bedrock generation failed after\""

  metric_transformation {
    name          = "BedrockRetryExhausted"
    namespace     = "RecipeAiFinder/App"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "bedrock_retry_exhausted" {
  count               = local.enabled ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-bedrock-retry-exhausted"
  alarm_description   = "Bedrock recipe generation exhausted all retry attempts."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "BedrockRetryExhausted"
  namespace           = "RecipeAiFinder/App"
  period              = var.alarm_period_seconds
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  tags                = merge(local.common_tags, { Name = "${var.project_name}-${var.environment}-bedrock-retry-exhausted" })
}

# ── Consolidated dashboard (single pane) ───────────────────────────────────────
resource "aws_cloudwatch_dashboard" "main" {
  count          = local.enabled ? 1 : 0
  dashboard_name = "${var.project_name}-${var.environment}"
  dashboard_body = templatefile("${path.module}/dashboard.tf.json.tftpl", {
    region                = var.aws_region
    project_name          = var.project_name
    environment           = var.environment
    cluster_name          = var.ecs_cluster_name
    service_name          = var.ecs_backend_service_name
    alb_arn_suffix        = var.alb_arn_suffix
    backend_tg_arn_suffix = var.backend_tg_arn_suffix
    nat_gateway_id        = var.nat_gateway_id
    dynamodb_tables       = local.dynamodb_tables
    waf_web_acl_name      = var.waf_web_acl_name
    enable_app_metrics    = var.enable_app_metrics
    enable_opensearch     = var.enable_opensearch
  })
}

# ── Cost budget scoped to CloudWatch (+ X-Ray later) ───────────────────────────
resource "aws_budgets_budget" "monitoring" {
  count = local.enabled && var.notification_email != "" ? 1 : 0

  name         = "${var.project_name}-${var.environment}-monitoring-budget"
  budget_type  = "COST"
  limit_amount = var.budget_limit_amount
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "Service"
    values = var.enable_xray ? ["AmazonCloudWatch", "AWS X-Ray"] : ["AmazonCloudWatch"]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.notification_email]
  }
}
