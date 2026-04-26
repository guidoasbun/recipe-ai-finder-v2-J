terraform {
  required_version = ">= 1.7"

  backend "s3" {
    bucket         = "recipe-ai-terraform-state"
    key            = "recipe-ai/terraform.tfstate"
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
