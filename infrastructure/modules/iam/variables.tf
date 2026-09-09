variable "project_name" {
  type = string
}

variable "cognito_user_pool_arn" {
  type        = string
  description = "ARN of the Cognito User Pool for admin operations"
}

variable "enable_xray" {
  type        = bool
  default     = false
  description = "Grant the ECS task role X-Ray write permissions (PutTraceSegments/PutTelemetryRecords + sampling reads) for the optional distributed-tracing layer. Off by default; added as a separate additive statement so the base task policy is untouched."
}
