# WAF Monitoring and Budget Alerts
#
# This file will contain:
# - aws_cloudwatch_metric_alarm for blocked requests exceeding threshold
# - aws_budgets_budget for WAF service cost with email notification
# - CloudWatch metrics are enabled per-rule via visibility_config on each rule
