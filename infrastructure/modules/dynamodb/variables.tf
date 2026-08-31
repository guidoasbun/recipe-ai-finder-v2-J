variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "enable_catalog_full" {
  type        = bool
  default     = false
  description = "Create the full 2.2M catalog table used by the OpenSearch backend. Off by default (rollback preservation, design §6.0)."
}
