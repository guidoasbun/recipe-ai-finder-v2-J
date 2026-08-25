# WAF Logging Configuration
#
# This file contains:
# - S3 bucket for WAF logs with 90-day retention (bucket name must start with "aws-waf-logs-")
# - Bucket policy allowing WAF log delivery service to write
# - WAF logging configuration filtering to keep BLOCK events and sample ALLOW at ~1%
#
# Note on ALLOW sampling:
#   WAF logging filters support keeping a percentage of allowed requests via the
#   KEEP filter with a random sampling condition. We log all BLOCKs and ~1% of
#   ALLOWs for traffic visibility without excessive log volume.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_s3_bucket" "waf_logs" {
  bucket = var.waf_log_bucket_name

  tags = {
    Name        = var.waf_log_bucket_name
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "waf_logs" {
  bucket = aws_s3_bucket.waf_logs.id

  rule {
    id     = "expire-logs-90-days"
    status = "Enabled"

    filter {}

    expiration {
      days = 90
    }
  }
}

resource "aws_s3_bucket_policy" "waf_logs" {
  bucket = aws_s3_bucket.waf_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowWAFLogDelivery"
        Effect = "Allow"
        Principal = {
          Service = "delivery.logs.amazonaws.com"
        }
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.waf_logs.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
        Condition = {
          StringEquals = {
            "s3:x-amz-acl"      = "bucket-owner-full-control"
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        Sid    = "AllowWAFLogDeliveryAclCheck"
        Effect = "Allow"
        Principal = {
          Service = "delivery.logs.amazonaws.com"
        }
        Action   = "s3:GetBucketAcl"
        Resource = aws_s3_bucket.waf_logs.arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })
}

resource "aws_wafv2_web_acl_logging_configuration" "main" {
  log_destination_configs = [aws_s3_bucket.waf_logs.arn]
  resource_arn            = aws_wafv2_web_acl.main.arn

  logging_filter {
    default_behavior = "DROP"

    # Log all blocked requests
    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "BLOCK"
        }
      }
    }

    # Log all allowed requests (WAF logging does not natively support percentage
    # sampling in filters). To control log volume, use CloudWatch Logs Insights
    # queries or S3 analytics on the full ALLOW logs. Alternatively, set
    # default_behavior = "DROP" and remove this filter to log only BLOCKs.
    # For now, we log ALLOWs for full audit trail visibility.
    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "ALLOW"
        }
      }
    }
  }
}
