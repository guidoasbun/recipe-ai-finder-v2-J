# WAF Logging Configuration
#
# This file will contain:
# - aws_s3_bucket for WAF logs
# - aws_s3_bucket_lifecycle_configuration with 90-day expiration
# - aws_s3_bucket_policy allowing WAF service to write logs
# - aws_wafv2_web_acl_logging_configuration to route BLOCK events to S3
