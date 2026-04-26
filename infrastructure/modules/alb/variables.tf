variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnets" {
  type = list(string)
}

variable "domain_name" {
  type = string
}

variable "security_group_ids" {
  type = list(string)
}
