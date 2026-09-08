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
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Oracle Cloud Infrastructure provider — used only by the self-hosted OpenSearch module.
# Credentials come from variables (see infrastructure/variables.tf, "OCI self-hosted OpenSearch").
# The private key is read from a file path (oci_private_key_path); its contents never enter state
# in plaintext beyond what the provider needs at runtime. When enable_oci_opensearch=false these
# values can stay blank and the provider is simply unused.
provider "oci" {
  tenancy_ocid     = var.oci_tenancy_ocid
  user_ocid        = var.oci_user_ocid
  fingerprint      = var.oci_fingerprint
  private_key_path = var.oci_private_key_path
  region           = var.oci_region
}
