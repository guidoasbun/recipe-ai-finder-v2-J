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

# Recipe images are referenced indefinitely by the Recipes DynamoDB table (the system of
# record), so deleting the S3 object while the recipe still points at it produces broken images
# on the /recipes page (S3 returns NoSuchKey / 404 for a still-valid presigned URL). Images are
# now retained for the life of the recipe; deletion happens explicitly in RecipeService.deleteRecipe
# when the owning recipe is removed. Incomplete multipart uploads are still cleaned up to avoid
# accumulating orphaned upload parts.
resource "aws_s3_bucket_lifecycle_configuration" "recipe_images" {
  bucket = aws_s3_bucket.recipe_images.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
