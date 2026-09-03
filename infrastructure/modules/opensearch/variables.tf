variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "enable_opensearch" {
  type        = bool
  default     = false
  description = "Provision the OpenSearch Serverless (NextGen) vector collection. Off by default so the standard deployment provisions no OpenSearch infrastructure and incurs no extra cost."
}

variable "task_role_arn" {
  type        = string
  description = "ECS task role ARN granted data access to the collection (least-privilege)."
}

variable "task_role_name" {
  type        = string
  description = "ECS task role name, used in the data-access policy principal list."
}

variable "admin_principals" {
  type        = list(string)
  default     = []
  description = "Extra IAM principal ARNs (e.g. a personal CLI user/role) granted data access to the collection for ad-hoc reindex/backfill/debugging. Empty by default (least-privilege: only the ECS task role has access). Add an ARN here to re-grant CLI access via a version-controlled change instead of manual drift; a principal also needs aoss:APIAccessAll on its IAM side. Note: aoss caps a data-access policy at 20 principals."
}

variable "collection_name" {
  type        = string
  default     = ""
  description = "Serverless collection name. Defaults to <project>-<env>-catalog when empty."
}

variable "max_search_ocu" {
  type        = number
  default     = 8
  description = "Maximum search OpenSearch Compute Units (cost ceiling). Scale-to-zero keeps the minimum at 0."
}

variable "max_indexing_ocu" {
  type        = number
  default     = 8
  description = "Maximum indexing OCUs (used during reindex). Scale-to-zero keeps the minimum at 0."
}

variable "budget_limit_amount" {
  type        = string
  default     = "30"
  description = "Monthly budget (USD) for the billing alarm. Set from design §2.1 (~$15 expected, alert ~$30)."
}

variable "budget_notification_email" {
  type        = string
  default     = ""
  description = "Email for the budget alert. Empty = no budget resource created."
}

variable "enable_batch_embedding" {
  type        = bool
  default     = false
  description = "Provision the S3 buckets + Bedrock batch service role for the full 2.2M batch embedding load. Not needed for the small-catalog reindex validation."
}
