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
