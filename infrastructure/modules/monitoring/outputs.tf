output "sns_topic_arn" {
  description = "ARN of the alarms SNS topic (empty when monitoring is disabled). Wire this into the WAF alarm's alarm_sns_topic_arn so the existing WAF alarm gets a live destination."
  value       = var.enable_monitoring ? aws_sns_topic.alarms[0].arn : ""
}

output "dashboard_name" {
  description = "Name of the consolidated CloudWatch dashboard (empty when disabled). View in CloudWatch → Dashboards."
  value       = var.enable_monitoring ? aws_cloudwatch_dashboard.main[0].dashboard_name : ""
}
