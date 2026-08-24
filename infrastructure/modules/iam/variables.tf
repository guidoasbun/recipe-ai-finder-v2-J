variable "project_name" {
  type = string
}

variable "cognito_user_pool_arn" {
  type        = string
  description = "ARN of the Cognito User Pool for admin operations"
}
