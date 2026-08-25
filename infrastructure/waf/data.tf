data "terraform_remote_state" "main" {
  backend = "s3"
  config = {
    bucket = "recipe-ai-terraform-state"
    key    = "recipe-ai/terraform.tfstate"
    region = "us-east-1"
  }
}
