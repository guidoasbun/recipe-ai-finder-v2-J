# WAF Web ACL and ALB Association
#
# Rule evaluation order (by priority):
#   0  - IP Allow-List
#   1  - IP Block-List
#   2  - Allow health endpoint (GET /api/health)
#   3  - Geographic restriction (conditional)
#   4  - AWS Managed Rules - Bot Control (blocks unverified bots for all endpoints)
#   5  - Auth endpoint rate limit
#   6  - Recipe generation rate limit
#   7  - Image upload rate limit
#   8  - Global rate limit
#   9  - AWS Managed Rules - IP Reputation List
#   10 - AWS Managed Rules - Common Rule Set
#   11 - AWS Managed Rules - Known Bad Inputs
#
# Total: 11 rules (12 with geo-block enabled). This slightly exceeds the
# 10-rule soft target from Requirement 10.1, but removing rules would reduce
# security coverage. Bot Control at COMMON level satisfies the cost constraint.
#
# Note on body size enforcement:
#   WAF inspects only the first 8 KB of request body for ALB-associated ACLs.
#   Body size limits >8 KB cannot be reliably enforced at the WAF layer.
#   Enforce request body size limits at the application/reverse-proxy layer instead.
#
# Note on rate limit IP aggregation:
#   Rate-based rules use FORWARDED_IP with X-Forwarded-For header to correctly
#   identify individual clients behind proxies/load balancers. Fallback behavior
#   is MATCH (block) when header is missing, providing protection even for direct
#   connections.

resource "aws_wafv2_web_acl" "main" {
  name        = "${var.project_name}-${var.environment}-web-acl"
  scope       = "REGIONAL"
  description = "WAF Web ACL for ${var.project_name} ${var.environment}"

  default_action {
    allow {}
  }

  custom_response_body {
    key          = "rate-limited"
    content      = "{\"status\":429,\"message\":\"Request blocked by WAF rate limit. Try again later.\"}"
    content_type = "APPLICATION_JSON"
  }

  # --- Priority 0: IP Allow-List ---
  rule {
    name     = "${var.project_name}-${var.environment}-allow-listed-ips"
    priority = 0

    action {
      allow {}
    }

    statement {
      or_statement {
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.allow_list.arn
          }
        }
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.allow_list_v6.arn
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-allow-listed-ips"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 1: IP Block-List ---
  rule {
    name     = "${var.project_name}-${var.environment}-block-listed-ips"
    priority = 1

    action {
      block {
        custom_response {
          response_code = 403
        }
      }
    }

    statement {
      or_statement {
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.block_list.arn
          }
        }
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.block_list_v6.arn
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-block-listed-ips"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 2: Allow health endpoint ---
  # Excludes GET /api/health from all subsequent rules (rate limits, managed rules)
  rule {
    name     = "${var.project_name}-${var.environment}-allow-health"
    priority = 2

    action {
      allow {}
    }

    statement {
      and_statement {
        statement {
          byte_match_statement {
            search_string         = "/api/health"
            positional_constraint = "EXACTLY"

            field_to_match {
              uri_path {}
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }

        statement {
          byte_match_statement {
            search_string         = "GET"
            positional_constraint = "EXACTLY"

            field_to_match {
              method {}
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-allow-health"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 3: Geographic Restriction ---
  # Only created when geo_block_countries is non-empty.
  # Undetermined origins do not match geo_match_statement and fall through
  # to the default ALLOW action, satisfying Requirement 5.2.
  dynamic "rule" {
    for_each = length(var.geo_block_countries) > 0 ? [1] : []
    content {
      name     = "${var.project_name}-${var.environment}-geo-block"
      priority = 3

      action {
        block {
          custom_response {
            response_code = 403
          }
        }
      }

      statement {
        geo_match_statement {
          country_codes = var.geo_block_countries
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.project_name}-${var.environment}-geo-block"
        sampled_requests_enabled   = true
      }
    }
  }

  # --- Priority 4: AWS Managed Rules - Bot Control ---
  # Blocks unverified bots across all endpoints. Must run before any rules
  # that depend on bot labels (future label_match_statement rules).
  # Common protection level; unverified bots blocked, verified bots allowed.
  rule {
    name     = "${var.project_name}-${var.environment}-bot-control"
    priority = 4

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesBotControlRuleSet"
        vendor_name = "AWS"

        managed_rule_group_configs {
          aws_managed_rules_bot_control_rule_set {
            inspection_level = "COMMON"
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-bot-control"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 5: Authentication Endpoint Rate Limit ---
  # Uses FORWARDED_IP to correctly rate-limit individual clients behind proxies.
  rule {
    name     = "${var.project_name}-${var.environment}-auth-rate-limit"
    priority = 5

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_auth
        aggregate_key_type = "FORWARDED_IP"

        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "/api/auth/"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    rule_label {
      name = "AUTH_RATE_LIMIT"
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-auth-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 6: Recipe Generation Rate Limit ---
  # Uses FORWARDED_IP to correctly rate-limit individual clients behind proxies.
  rule {
    name     = "${var.project_name}-${var.environment}-recipe-gen-rate-limit"
    priority = 6

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_recipe_gen
        aggregate_key_type = "FORWARDED_IP"

        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "/api/recipes/generate"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-recipe-gen-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 7: Image Upload Rate Limit ---
  # Uses FORWARDED_IP to correctly rate-limit individual clients behind proxies.
  rule {
    name     = "${var.project_name}-${var.environment}-image-upload-rate-limit"
    priority = 7

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_image_upload
        aggregate_key_type = "FORWARDED_IP"

        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "/api/images/upload"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-image-upload-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 8: Global Rate Limit ---
  # Applies to ALL requests regardless of endpoint.
  # Uses FORWARDED_IP so proxied traffic is correctly attributed to individual clients.
  # Per Requirement 4.3, the global counter includes requests that also match
  # endpoint-specific rate rules since WAF evaluates all rate rules independently.
  rule {
    name     = "${var.project_name}-${var.environment}-global-rate-limit"
    priority = 8

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_global
        aggregate_key_type = "FORWARDED_IP"

        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-global-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 9: AWS Managed Rules - IP Reputation List ---
  rule {
    name     = "${var.project_name}-${var.environment}-ip-reputation"
    priority = 9

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 10: AWS Managed Rules - Common Rule Set ---
  # Excluded rules handle known false positives for this application:
  # - SizeRestrictions_BODY: conflicts with larger upload endpoint
  # - CrossSiteScripting_BODY: recipe descriptions may contain trigger characters
  # - GenericRFI_BODY: URLs in recipe content may trigger RFI rules
  rule {
    name     = "${var.project_name}-${var.environment}-common-rules"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        excluded_rule {
          name = "SizeRestrictions_BODY"
        }

        excluded_rule {
          name = "CrossSiteScripting_BODY"
        }

        excluded_rule {
          name = "GenericRFI_BODY"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-common-rules"
      sampled_requests_enabled   = true
    }
  }

  # --- Priority 11: AWS Managed Rules - Known Bad Inputs ---
  rule {
    name     = "${var.project_name}-${var.environment}-known-bad-inputs"
    priority = 11

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-${var.environment}-known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.project_name}-${var.environment}-waf"
    sampled_requests_enabled   = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-web-acl"
    Environment = var.environment
    Project     = var.project_name
    ManagedBy   = "terraform"
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}
