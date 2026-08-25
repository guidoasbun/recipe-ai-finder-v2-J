output "web_acl_id" {
  value       = aws_wafv2_web_acl.main.id
  description = "ID of the created Web ACL"
}

output "web_acl_arn" {
  value       = aws_wafv2_web_acl.main.arn
  description = "ARN of the created Web ACL"
}

output "waf_log_bucket_arn" {
  value       = aws_s3_bucket.waf_logs.arn
  description = "ARN of the WAF logging S3 bucket"
}

output "ip_allow_set_id" {
  value       = aws_wafv2_ip_set.allow_list.id
  description = "ID of the allow-list IP set (for independent updates)"
}

output "ip_block_set_id" {
  value       = aws_wafv2_ip_set.block_list.id
  description = "ID of the block-list IP set (for independent updates)"
}
