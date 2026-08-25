terraform {
  required_version = ">= 1.7"

  backend "s3" {
    bucket         = "recipe-ai-terraform-state"
    key            = "recipe-ai-waf/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "recipe-ai-terraform-locks"
    encrypt        = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

module "waf" {
  source       = "./modules/waf"
  project_name = var.project_name
  environment  = var.environment
  alb_arn      = data.terraform_remote_state.main.outputs.alb_arn

  allowed_ips         = var.waf_allowed_ips
  allowed_ips_v6      = var.waf_allowed_ips_v6
  blocked_ips         = var.waf_blocked_ips
  blocked_ips_v6      = var.waf_blocked_ips_v6
  geo_block_countries = var.waf_geo_block_countries

  rate_limit_global       = var.waf_rate_limit_global
  rate_limit_recipe_gen   = var.waf_rate_limit_recipe_gen
  rate_limit_image_upload = var.waf_rate_limit_image_upload
  rate_limit_auth         = var.waf_rate_limit_auth

  waf_log_bucket_name              = "${var.project_name}-${var.environment}-waf-logs"
  alarm_sns_topic_arn              = var.waf_alarm_sns_topic_arn
  blocked_requests_alarm_threshold = var.waf_blocked_requests_alarm_threshold
  budget_limit_amount              = var.waf_budget_limit_amount
  budget_notification_email        = var.waf_budget_notification_email
}
