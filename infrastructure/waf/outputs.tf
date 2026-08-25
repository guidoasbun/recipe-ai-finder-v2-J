output "web_acl_id" {
  value       = module.waf.web_acl_id
  description = "ID of the created Web ACL"
}

output "web_acl_arn" {
  value       = module.waf.web_acl_arn
  description = "ARN of the created Web ACL"
}

output "waf_log_bucket_arn" {
  value       = module.waf.waf_log_bucket_arn
  description = "ARN of the WAF logging S3 bucket"
}

output "ip_allow_set_id" {
  value       = module.waf.ip_allow_set_id
  description = "ID of the IPv4 allow-list IP set (for independent updates)"
}

output "ip_allow_set_v6_id" {
  value       = module.waf.ip_allow_set_v6_id
  description = "ID of the IPv6 allow-list IP set (for independent updates)"
}

output "ip_block_set_id" {
  value       = module.waf.ip_block_set_id
  description = "ID of the IPv4 block-list IP set (for independent updates)"
}

output "ip_block_set_v6_id" {
  value       = module.waf.ip_block_set_v6_id
  description = "ID of the IPv6 block-list IP set (for independent updates)"
}
