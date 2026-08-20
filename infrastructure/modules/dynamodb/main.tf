resource "aws_dynamodb_table" "users" {
  name         = "${var.project_name}-${var.environment}-users"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"

  attribute {
    name = "userId"
    type = "S"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-users"
  }
}

resource "aws_dynamodb_table" "recipes" {
  name         = "${var.project_name}-${var.environment}-recipes"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "recipeId"

  attribute {
    name = "recipeId"
    type = "S"
  }

  attribute {
    name = "userId"
    type = "S"
  }

  global_secondary_index {
    name            = "userId-index"
    hash_key        = "userId"
    projection_type = "ALL"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-recipes"
  }
}

resource "aws_dynamodb_table" "consent" {
  name         = "${var.project_name}-${var.environment}-consent"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"
  range_key    = "consentType"

  attribute {
    name = "userId"
    type = "S"
  }

  attribute {
    name = "consentType"
    type = "S"
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-consent"
    Environment = var.environment
  }
}

resource "aws_dynamodb_table" "audit_log" {
  name         = "${var.project_name}-${var.environment}-audit-log"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "auditId"

  attribute {
    name = "auditId"
    type = "S"
  }

  attribute {
    name = "userId"
    type = "S"
  }

  attribute {
    name = "timestamp"
    type = "S"
  }

  global_secondary_index {
    name            = "userId-timestamp-index"
    hash_key        = "userId"
    range_key       = "timestamp"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-audit-log"
    Environment = var.environment
  }
}
