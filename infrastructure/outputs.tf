output "alb_dns" {
  value = module.alb.alb_dns
}

output "cognito_user_pool_id" {
  value = module.cognito.user_pool_id
}

output "cognito_client_id" {
  value = module.cognito.client_id
}

output "cognito_domain" {
  value = module.cognito.cognito_domain
}

output "backend_ecr_url" {
  value = module.ecr.backend_repository_url
}

output "frontend_ecr_url" {
  value = module.ecr.frontend_repository_url
}

output "github_actions_role_arn" {
  value = module.iam.github_actions_role_arn
}

output "alb_arn" {
  value       = module.alb.alb_arn
  description = "ARN of the Application Load Balancer"
}

output "waf_web_acl_id" {
  value       = module.waf.web_acl_id
  description = "ID of the WAF Web ACL"
}

output "waf_web_acl_arn" {
  value       = module.waf.web_acl_arn
  description = "ARN of the WAF Web ACL"
}

output "opensearch_collection_endpoint" {
  description = "OpenSearch Serverless collection endpoint (empty when disabled)."
  value       = module.opensearch.collection_endpoint
}

output "opensearch_ocu_cap_cli_command" {
  description = "Run once to enforce the OCU cost ceiling (see RUNBOOK §3.2)."
  value       = module.opensearch.ocu_cap_cli_command
}
