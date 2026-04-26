variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "domain_name" {
  type = string
}

variable "cognito_google_client_id_ssm" {
  type        = string
  description = "SSM path to Google OAuth client ID"
}

variable "cognito_google_client_secret_ssm" {
  type        = string
  description = "SSM path to Google OAuth client secret"
}
