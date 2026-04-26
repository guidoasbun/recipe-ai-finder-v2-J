output "bucket_name" {
  value = aws_s3_bucket.recipe_images.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.recipe_images.arn
}
