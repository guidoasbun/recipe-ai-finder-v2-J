variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnets" {
  type = list(string)
}

variable "public_subnets" {
  type = list(string)
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "backend_ecr_url" {
  type = string
}

variable "frontend_ecr_url" {
  type = string
}

variable "backend_tg_arn" {
  type = string
}

variable "frontend_tg_arn" {
  type = string
}

variable "dynamodb_users_table" {
  type = string
}

variable "dynamodb_recipes_table" {
  type = string
}

variable "dynamodb_catalog_table" {
  type        = string
  description = "DynamoDB table name for the shared recipe catalog"
}

variable "dynamodb_catalog_full_table" {
  type        = string
  default     = ""
  description = "Full 2.2M catalog table for the OpenSearch backend. Empty = falls back to the small catalog table."
}

variable "catalog_search_backend" {
  type        = string
  default     = "inapp"
  description = "Catalog search backend: inapp | opensearch."
}

variable "opensearch_endpoint" {
  type        = string
  default     = ""
  description = "OpenSearch Serverless collection endpoint. Empty when the OpenSearch backend is not enabled."
}

variable "opensearch_index" {
  type        = string
  default     = "catalog-recipes"
  description = "OpenSearch index/collection index name."
}

variable "s3_bucket" {
  type = string
}

variable "cognito_issuer_uri" {
  type = string
}

variable "domain_name" {
  type = string
}

variable "ecs_security_group_id" {
  type = string
}

variable "cognito_domain" {
  type = string
}

variable "cognito_client_id" {
  type = string
}

variable "stability_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Stability AI API key"
}

variable "openai_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the OpenAI API key"
}

variable "google_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Google AI Studio API key"
}

variable "dynamodb_consent_table" {
  type        = string
  description = "DynamoDB table name for consent records"
}

variable "dynamodb_audit_table" {
  type        = string
  description = "DynamoDB table name for audit log records"
}

variable "cognito_user_pool_id" {
  type        = string
  description = "Cognito User Pool ID for admin operations"
}
