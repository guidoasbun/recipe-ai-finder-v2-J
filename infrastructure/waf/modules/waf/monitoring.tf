# WAF Monitoring and Budget Alerts
#
# - CloudWatch alarm for high blocked request counts
# - AWS Budgets alert for WAF service cost
# - Per-rule metrics are enabled via visibility_config on each rule in main.tf

resource "aws_cloudwatch_metric_alarm" "waf_blocked_requests" {
  alarm_name          = "${var.project_name}-${var.environment}-waf-blocked-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "BlockedRequests"
  namespace           = "AWS/WAFV2"
  period              = 300
  statistic           = "Sum"
  threshold           = var.blocked_requests_alarm_threshold
  alarm_description   = "WAF blocked requests exceeded ${var.blocked_requests_alarm_threshold} in 5 minutes"

  dimensions = {
    WebACL = aws_wafv2_web_acl.main.name
    Region = data.aws_region.current.name
    Rule   = "ALL"
  }

  alarm_actions = [var.alarm_sns_topic_arn]

  tags = {
    Name        = "${var.project_name}-${var.environment}-waf-blocked-high"
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_budgets_budget" "waf_cost" {
  name         = "${var.project_name}-${var.environment}-waf-budget"
  budget_type  = "COST"
  limit_amount = var.budget_limit_amount
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "Service"
    values = ["AWS WAF"]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }
}
