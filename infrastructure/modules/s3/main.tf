resource "aws_s3_bucket" "recipe_images" {
  bucket = "${var.project_name}-${var.environment}-recipe-images"

  tags = {
    Name = "${var.project_name}-${var.environment}-recipe-images"
  }
}

resource "aws_s3_bucket_public_access_block" "recipe_images" {
  bucket = aws_s3_bucket.recipe_images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "recipe_images" {
  bucket = aws_s3_bucket.recipe_images.id

  versioning_configuration {
    status = "Disabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "recipe_images" {
  bucket = aws_s3_bucket.recipe_images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "recipe_images" {
  bucket = aws_s3_bucket.recipe_images.id

  rule {
    id     = "expire-old-images"
    status = "Enabled"

    filter {}

    expiration {
      days = 90
    }
  }
}
