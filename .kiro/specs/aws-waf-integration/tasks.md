# Implementation Plan: AWS WAF Integration

## Overview

This plan implements an AWS WAF Web ACL as a separate Terraform root (`infrastructure/waf/`) with its own state file, using a nested module (`infrastructure/waf/modules/waf/`) for the actual WAF resources. The WAF root reads the ALB ARN from the main infrastructure state via `terraform_remote_state`. This approach enables independent deployments, reduces plan latency, and limits blast radius on state corruption. Tasks are ordered so each step builds on the previous — starting with the root and module skeleton, then layering in resources, and finishing with CI/CD wiring and verification.

## Tasks

- [ ] 1. Set up WAF root and module structure
  - [ ] 1.1 Create the WAF module directory and define input variables
    - Create `infrastructure/waf/modules/waf/variables.tf` with all module inputs: `project_name`, `environment`, `alb_arn`, `allowed_ips`, `blocked_ips`, `geo_block_countries`, rate limit thresholds, `waf_log_bucket_name`, `alarm_sns_topic_arn`, `blocked_requests_alarm_threshold`, `budget_limit_amount`, `budget_notification_email`
    - Create `infrastructure/waf/modules/waf/outputs.tf` exposing `web_acl_id`, `web_acl_arn`, `waf_log_bucket_arn`, `ip_allow_set_id`, `ip_block_set_id`
    - Create placeholder `infrastructure/waf/modules/waf/main.tf`, `infrastructure/waf/modules/waf/ip_sets.tf`, `infrastructure/waf/modules/waf/logging.tf`, `infrastructure/waf/modules/waf/monitoring.tf`
    - _Requirements: 11.1, 11.3_

  - [ ] 1.2 Create the WAF Terraform root and wire the module
    - Create `infrastructure/waf/main.tf` with S3 backend config (separate state key), provider config, and `module "waf"` block calling `./modules/waf`
    - Create `infrastructure/waf/variables.tf` with root-level variables (project_name, environment, waf_allowed_ips, waf_blocked_ips, waf_geo_block_countries, rate limits, alarm/budget params)
    - Create `infrastructure/waf/outputs.tf` exposing module outputs (web_acl_id, web_acl_arn, etc.)
    - Create `infrastructure/waf/data.tf` with `terraform_remote_state` data source reading ALB ARN from main state (`s3` backend, key `main/terraform.tfstate`)
    - Pass `alb_arn = data.terraform_remote_state.main.outputs.alb_arn` to the module block
    - Add `output "alb_arn"` to `infrastructure/outputs.tf` (main infra root) exposing `module.alb.alb_arn` so the WAF root can read it via remote state
    - Ensure `infrastructure/modules/alb/outputs.tf` exposes `alb_arn` from `aws_lb.main.arn`
    - _Requirements: 11.1, 1.1_

  - [ ] 1.3 Add environment-specific WAF parameters to tfvars files
    - Create `infrastructure/waf/environments/dev.tfvars`: rate limits at 50% of prod (global=1000, recipe_gen=50, image_upload=30, auth=15), alarm threshold=500, budget="25", empty IP lists, empty geo block list
    - Create `infrastructure/waf/environments/prod.tfvars`: rate limits at full values (global=2000, recipe_gen=100, image_upload=60, auth=30), alarm threshold=1000, budget="50", empty IP lists, empty geo block list
    - _Requirements: 11.2, 11.4_

- [ ] 2. Implement IP Sets and core Web ACL resource
  - [ ] 2.1 Create IP Set resources for allow-list and block-list
    - Implement `infrastructure/waf/modules/waf/ip_sets.tf` with `aws_wafv2_ip_set` for allow-list (IPV4) and block-list (IPV4)
    - Add IPv6 IP sets for both allow-list and block-list
    - Ensure IP sets are standalone resources with no lifecycle dependency on the Web ACL
    - Tag resources with project name and environment
    - _Requirements: 6.1, 6.2, 6.5, 6.6_

  - [ ] 2.2 Create the Web ACL resource with default ALLOW action and visibility config
    - Implement the `aws_wafv2_web_acl` resource in `infrastructure/waf/modules/waf/main.tf` with `scope = "REGIONAL"`, `default_action { allow {} }`, and CloudWatch metrics enabled via `visibility_config`
    - Add the `aws_wafv2_web_acl_association` resource linking the Web ACL to the ALB ARN
    - Add custom response body for rate-limit blocks (JSON with status 403 and message)
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 3. Implement WAF rules (priority 0-4)
  - [ ] 3.1 Add IP allow-list rule (priority 0) and IP block-list rule (priority 1)
    - IP allow-list: action ALLOW, references the allow-list IP set, priority 0
    - IP block-list: action BLOCK with 403 response, references the block-list IP set, priority 1
    - Both rules should reference IPv4 and IPv6 IP sets using OR logic
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.7_

  - [ ] 3.2 Add health endpoint exclusion rule (priority 2)
    - Byte match rule that ALLOWs GET requests to exact path `/api/health`
    - Must NOT match trailing slash variants like `/api/health/`
    - Priority 2 (after IP block-list, before rate/managed rules)
    - _Requirements: 9.1, 9.2, 9.3_

  - [ ] 3.3 Add geographic restriction rule (priority 3)
    - Geo match rule that BLOCKs requests from countries in `var.geo_block_countries`
    - Use `forwarded_ip_config` or standard geo match with fallback action ALLOW for undetermined origins
    - Only create the rule if the geo block list is non-empty (use `dynamic` block or count)
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [ ] 3.4 Add request body size limit rule (priority 4)
    - Size constraint rule that BLOCKs requests with body > 1,048,576 bytes
    - Add scope-down statement excluding POST /api/images/upload from the 1MB limit
    - Add a separate size constraint for the image upload endpoint at 10,485,760 bytes (10 MB)
    - Use `oversize_handling = "MATCH"` for requests larger than WAF inspection limit
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 4. Implement rate-based and auth-protection rules (priority 5-9)
  - [ ] 4.1 Add authentication endpoint rate limit (priority 5) and bot block (priority 6)
    - Rate-based rule for POST /api/auth/* with limit from `var.rate_limit_auth` per 5-min window per IP
    - Add custom label `AUTH_RATE_LIMIT` to the rate-based rule
    - Label match rule that BLOCKs requests to /api/auth/* carrying the `awswaf:managed:aws:bot-control:bot:unverified` label
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ] 4.2 Add endpoint-specific rate limits (priority 7-8) and global rate limit (priority 9)
    - Rate-based rule for POST /api/recipes/generate with `var.rate_limit_recipe_gen` limit (priority 7)
    - Rate-based rule for POST /api/images/upload with `var.rate_limit_image_upload` limit (priority 8)
    - Global rate-based rule with `var.rate_limit_global` limit across all endpoints (priority 9)
    - All rate-based rules return HTTP 403 using the custom response body
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 5. Implement managed rule groups (priority 10-13)
  - [ ] 5.1 Add AWS Managed Rule Groups to the Web ACL
    - AWSManagedRulesAmazonIpReputationList at priority 10, action override to BLOCK
    - AWSManagedRulesCommonRuleSet at priority 11, action override to BLOCK, with `excluded_rule` blocks for known false positives (SizeRestrictions_BODY, CrossSiteScripting_BODY, GenericRFI_BODY)
    - AWSManagedRulesKnownBadInputsRuleSet at priority 12, action override to BLOCK
    - AWSManagedRulesBotControlRuleSet at priority 13, common protection level, BLOCK for unverified bots, ALLOW for verified bots
    - Do NOT pin to specific versions (omit `version` attribute)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 10.2_

- [ ] 6. Checkpoint - Validate Terraform configuration
  - Run `terraform validate` and `terraform plan -var-file=environments/dev.tfvars` from `infrastructure/waf/` directory. Ensure a clean plan is produced. Ask the user if questions arise.

- [ ] 7. Implement logging and monitoring
  - [ ] 7.1 Create S3 bucket and WAF logging configuration
    - Create S3 bucket with name from `var.waf_log_bucket_name` in `infrastructure/waf/modules/waf/logging.tf`
    - Add lifecycle configuration with 90-day expiration rule
    - Add bucket policy allowing WAF to write logs
    - Configure `aws_wafv2_web_acl_logging_configuration` to log all BLOCK actions and sample ALLOW at ~1%
    - Use `waf-logs/` prefix via S3 bucket key prefix or logging filter
    - _Requirements: 7.1, 7.2, 7.3, 7.6, 10.3_

  - [ ] 7.2 Create CloudWatch alarms and AWS Budgets alert
    - Create `aws_cloudwatch_metric_alarm` for blocked requests exceeding threshold in 5-min period in `infrastructure/waf/modules/waf/monitoring.tf`
    - Configure alarm to send notifications to the SNS topic ARN
    - Create `aws_budgets_budget` for WAF service cost with notification at 100% of budget limit
    - Emit CloudWatch metrics per rule group (enabled via visibility_config on each rule)
    - _Requirements: 7.4, 7.5, 10.4_

- [ ] 8. Checkpoint - Full plan validation
  - Run `terraform plan -var-file=environments/dev.tfvars` and `terraform plan -var-file=environments/prod.tfvars` from `infrastructure/waf/` to verify both environments produce clean plans. Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Integrate with GitHub Actions CI/CD pipeline
  - [ ] 9.1 Add WAF deployment steps to the deploy workflow
    - Add Terraform init, plan, and apply steps to `.github/workflows/deploy.yml` BEFORE the "Deploy to ECS" step
    - Use `working-directory: ./infrastructure/waf` for all WAF Terraform steps
    - Reference `infrastructure/waf/environments/${{ github.event.inputs.environment || 'dev' }}.tfvars` for var-file
    - Set `timeout-minutes: 10` on plan and apply steps
    - Add conditional logic: if WAF apply fails, halt workflow and do not proceed with ECS deployment
    - Add post-apply verification step that checks WAF Web ACL exists and is associated with the ALB
    - _Requirements: 12.1, 12.2, 12.4_

  - [ ] 9.2 Add workflow dispatch support for independent WAF deployment
    - Ensure the existing `workflow_dispatch` with `environment` input also applies WAF changes from `infrastructure/waf/`
    - Add `paths` filter or documentation noting that WAF changes deploy with all infrastructure
    - _Requirements: 12.3_

- [ ] 10. Add post-deploy smoke test script
  - [ ] 10.1 Create a verification script for post-deployment checks
    - Create `infrastructure/waf/scripts/verify-waf.sh` (or inline in CI)
    - Verify Web ACL exists via `aws wafv2 get-web-acl`
    - Verify ALB association via `aws wafv2 get-web-acl-for-resource`
    - Verify health endpoint accessibility via `curl` returning HTTP 200
    - Verify S3 logging bucket exists and has lifecycle policy
    - _Requirements: 12.4, 1.1, 7.1_

- [ ] 11. Final checkpoint - End-to-end validation
  - Ensure all Terraform files pass `terraform validate`, `terraform fmt -check`, and produce clean plans for both dev and prod environments when run from `infrastructure/waf/`. Ensure all tests pass, ask the user if questions arise.

## Notes

- This feature is entirely Infrastructure as Code (Terraform). There are no application code changes required.
- The WAF uses a **separate Terraform state file** (`infrastructure/waf/`) with its own S3 backend key, enabling independent deployments and reducing blast radius.
- The WAF root reads the ALB ARN from the main infrastructure state via `terraform_remote_state` data source in `infrastructure/waf/data.tf`.
- The main infrastructure (`infrastructure/outputs.tf`) must expose `alb_arn` as a root output so the WAF state can reference it.
- The design document's Correctness Properties are verified via `terraform plan` inspection and post-deploy AWS CLI checks, not property-based tests. Test sub-tasks are therefore not included since PBT does not apply to IaC.
- The ALB module currently does not expose `alb_arn` — task 1.2 adds this output.
- The existing deploy.yml uses workflow_dispatch with a `dev`/`prod` choice — WAF deployment integrates into this existing pattern with `working-directory: ./infrastructure/waf`.
- The 10-rule cost constraint (Requirement 10.1) requires careful consolidation of rules. The design notes that auth rules and rate-limit rules may need grouping to stay within budget.
- IP sets are defined as standalone resources to allow independent updates without modifying the Web ACL.
- Environment parameters use 50% of production thresholds for dev per Requirement 11.4.
- Checkpoints ensure incremental validation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1", "2.2"] },
    { "id": 3, "tasks": ["3.1", "3.2", "3.3", "3.4"] },
    { "id": 4, "tasks": ["4.1", "4.2"] },
    { "id": 5, "tasks": ["5.1"] },
    { "id": 6, "tasks": ["7.1", "7.2"] },
    { "id": 7, "tasks": ["9.1", "9.2", "10.1"] }
  ]
}
```
