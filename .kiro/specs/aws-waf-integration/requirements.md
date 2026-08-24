# Requirements Document

## Introduction

This document specifies the requirements for integrating AWS WAF (Web Application Firewall) with the Recipe AI Finder application. The WAF will provide edge-level protection in front of the Application Load Balancer (ALB), complementing the existing application-level security controls (RateLimitFilter, RequestSizeLimitFilter, SecurityConfig). The integration aims to mitigate bot traffic, block known-malicious IPs, enforce geographic restrictions, and protect against common web exploits such as SQL injection and cross-site scripting at the network edge before requests reach the application.

## Glossary

- **WAF_Web_ACL**: The AWS WAF Web Access Control List resource that defines the set of rules evaluated against incoming HTTP requests to the ALB.
- **ALB**: The Application Load Balancer fronting the ECS-deployed backend and frontend services.
- **Managed_Rule_Group**: A pre-configured set of AWS WAF rules maintained by AWS or AWS Marketplace sellers.
- **Custom_Rule**: A user-defined WAF rule with specific match conditions and actions tailored to the application API patterns.
- **Rate_Based_Rule**: A WAF rule that tracks request rates per IP and blocks IPs exceeding the threshold.
- **IP_Set**: A named collection of IP addresses or CIDR ranges used for allow-listing or block-listing in WAF rules.
- **WAF_Logging**: The configuration that sends WAF evaluation logs to a designated AWS destination (S3, CloudWatch Logs, or Kinesis Data Firehose).
- **Recipe_Generation_Endpoint**: The POST /api/recipes/generate endpoint that invokes AI model inference.
- **Image_Upload_Endpoint**: The POST /api/images/upload endpoint that accepts multipart file uploads.
- **CloudWatch_Metric**: An AWS CloudWatch metric emitted by WAF for monitoring blocked and allowed requests.

## Requirements

### Requirement 1: Web ACL Provisioning

**User Story:** As a platform operator, I want a WAF Web ACL associated with the ALB, so that all inbound traffic is evaluated by WAF rules before reaching the application.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL be associated with the ALB serving the Recipe AI Finder backend using REGIONAL scope.
2. THE WAF_Web_ACL SHALL use a default action of ALLOW for requests that do not match any configured rule.
3. THE WAF_Web_ACL SHALL be deployed in the same AWS region as the ALB (us-east-1) with REGIONAL scope.
4. THE WAF_Web_ACL SHALL be defined as Infrastructure as Code within the project repository.
5. IF the ALB already has a Web ACL association, THEN THE deployment SHALL disassociate the existing Web ACL before associating the new WAF_Web_ACL.
6. WHEN a request passes through the WAF_Web_ACL, THE response SHALL include an `x-amzn-waf-action` header indicating the evaluation result.

### Requirement 2: AWS Managed Rule Groups

**User Story:** As a platform operator, I want AWS-managed rule groups enabled, so that the application is protected against common web exploits without custom rule maintenance.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include the AWSManagedRulesCommonRuleSet managed rule group with action set to BLOCK.
2. THE WAF_Web_ACL SHALL include the AWSManagedRulesKnownBadInputsRuleSet managed rule group with action set to BLOCK.
3. THE WAF_Web_ACL SHALL include the AWSManagedRulesAmazonIpReputationList managed rule group with action set to BLOCK.
4. THE WAF_Web_ACL SHALL include the AWSManagedRulesBotControlRuleSet managed rule group in common protection level with action set to BLOCK for non-verified bots and ALLOW for verified bots.
5. WHEN a managed rule group is updated by AWS, THE WAF_Web_ACL SHALL automatically use the latest version by not pinning to a specific version.
6. THE managed rule groups SHALL be evaluated in the following priority order: IP Reputation first, then Common Rule Set, then Known Bad Inputs, then Bot Control.
7. THE AWSManagedRulesCommonRuleSet SHALL include excluded rules for any false positives identified during testing against legitimate application API requests.

### Requirement 3: Request Size Enforcement at Edge

**User Story:** As a platform operator, I want request body size enforced at the WAF edge, so that oversized payloads are rejected before consuming application resources.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include a Custom_Rule that blocks requests with a body size exceeding 1,048,576 bytes (1 MB), using oversize handling set to MATCH so that requests larger than the WAF body inspection limit are treated as matching the size constraint.
2. WHEN a request body exceeds 1 MB, THE WAF_Web_ACL SHALL return an HTTP 403 response and block the request.
3. THE Custom_Rule for request size SHALL have a priority evaluated after the IP_Set allow-list, IP_Set block-list, and health endpoint exclusion rules, but before the managed rule groups.
4. IF the Image_Upload_Endpoint requires accepting files larger than 1 MB, THEN THE WAF_Web_ACL SHALL include a scope-down statement that excludes requests matching the POST /api/images/upload path from the body size Custom_Rule, applying a separate size limit of 10,485,760 bytes (10 MB) for that endpoint.

### Requirement 4: Rate-Based Rules for API Protection

**User Story:** As a platform operator, I want WAF-level rate limiting on expensive endpoints, so that abuse is blocked at the edge before reaching application-level rate limiting.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include a Rate_Based_Rule for the Recipe_Generation_Endpoint that limits requests to 100 per 5-minute window per IP address.
2. THE WAF_Web_ACL SHALL include a Rate_Based_Rule for the Image_Upload_Endpoint that limits requests to 60 per 5-minute window per IP address.
3. THE WAF_Web_ACL SHALL include a global Rate_Based_Rule that limits total requests to 2000 per 5-minute window per IP address across all endpoints, and the global counter SHALL include requests that also match endpoint-specific rate rules.
4. WHEN an IP address exceeds a rate-based threshold, THE WAF_Web_ACL SHALL return an HTTP 403 response and block subsequent requests from that IP for the remainder of the current 5-minute evaluation window.
5. THE endpoint-specific Rate_Based_Rules SHALL be evaluated at higher priority (lower numeric priority value) than the global Rate_Based_Rule.

### Requirement 5: Geographic Restriction

**User Story:** As a platform operator, I want the ability to restrict traffic by geographic origin, so that the application can comply with regional regulations or block traffic from high-risk regions.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL support a configurable geographic block list defined as a set of ISO 3166-1 alpha-2 country codes.
2. WHEN a request originates from a country in the geographic block list, THE WAF_Web_ACL SHALL return an HTTP 403 response and block the request. IF the geographic origin cannot be determined, THEN THE WAF_Web_ACL SHALL allow the request by default.
3. THE geographic block list SHALL be maintainable without redeploying the entire WAF configuration (via an updatable variable or parameter).
4. THE geographic restriction rule SHALL be evaluated after the IP allow-list and block-list rules.

### Requirement 6: IP Allow-List and Block-List

**User Story:** As a platform operator, I want explicit IP allow-list and block-list controls, so that trusted sources bypass WAF rules and known-malicious sources are permanently blocked.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include an IP_Set allow-list rule that permits requests from listed IP addresses or CIDR ranges without further rule evaluation.
2. THE WAF_Web_ACL SHALL include an IP_Set block-list rule that returns an HTTP 403 response and denies requests from listed IP addresses or CIDR ranges.
3. THE IP_Set allow-list rule SHALL have the highest priority (evaluated first) among all rules in the WAF_Web_ACL. IF an IP address appears in both allow-list and block-list, THEN the allow-list SHALL take precedence.
4. THE IP_Set block-list rule SHALL be evaluated immediately after the allow-list rule.
5. THE IP_Set resources SHALL be updatable independently of the WAF_Web_ACL rule configuration.
6. EACH IP_Set SHALL support both IPv4 and IPv6 addresses with a maximum capacity of 10,000 entries.
7. WHEN an IP_Set is empty, THE corresponding rule SHALL have no effect on request evaluation (neither block nor allow).

### Requirement 7: WAF Logging and Monitoring

**User Story:** As a platform operator, I want WAF request evaluation logs and CloudWatch metrics, so that I can monitor blocked traffic, investigate incidents, and tune rules.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL have logging enabled with logs delivered to an S3 bucket with a prefix of `waf-logs/`.
2. THE WAF_Logging configuration SHALL log all requests that are blocked by any rule.
3. THE WAF_Logging configuration SHALL log sampled allowed requests at a rate of 1% (1 in every 100 allowed requests).
4. THE WAF_Web_ACL SHALL emit CloudWatch_Metric data for each rule group showing counts of allowed, blocked, and counted requests.
5. WHEN the count of blocked requests exceeds 1000 in a 5-minute period, THE monitoring configuration SHALL trigger a CloudWatch Alarm that sends a notification to a designated SNS topic.
6. THE S3 bucket storing WAF logs SHALL have a lifecycle policy that retains log objects for 90 days before automatic deletion.

### Requirement 8: Custom Rule for Authentication Endpoint Protection

**User Story:** As a platform operator, I want additional protection on authentication-related endpoints, so that credential-stuffing and brute-force attacks are mitigated at the edge.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include a Rate_Based_Rule targeting POST requests to the /api/auth/* path pattern that limits requests to 30 per 5-minute window per IP address.
2. THE WAF_Web_ACL SHALL include a Custom_Rule that blocks requests to /api/auth/* which have been labeled by the AWSManagedRulesBotControlRuleSet as bot-originated, by evaluating the labels emitted from that managed rule group.
3. WHEN an IP address is blocked by the authentication Rate_Based_Rule, THE WAF_Web_ACL SHALL log the event with a label identifying the rule as AUTH_RATE_LIMIT.
4. THE authentication Rate_Based_Rule and credential-stuffing Custom_Rule SHALL be evaluated after the IP allow-list and block-list rules but before the global Rate_Based_Rule.

### Requirement 9: Health Endpoint Exclusion

**User Story:** As a platform operator, I want the health check endpoint excluded from restrictive WAF rules, so that load balancer health checks are not inadvertently blocked.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL include a Custom_Rule that allows requests to the /api/health path without evaluating subsequent rules.
2. THE health endpoint exclusion rule SHALL match only GET requests to the exact path /api/health (excluding trailing slash variants such as /api/health/).
3. THE health endpoint exclusion rule SHALL be evaluated before rate-based and managed rules but after the IP block-list rule, using an explicit numeric priority value.

### Requirement 10: Cost-Conscious Configuration

**User Story:** As a platform operator, I want the WAF configuration to remain within a predictable monthly cost, so that edge security does not cause unexpected billing increases.

#### Acceptance Criteria

1. THE WAF_Web_ACL SHALL use no more than 10 rule groups or custom rules to minimize per-rule costs.
2. THE AWSManagedRulesBotControlRuleSet SHALL be configured at the common protection level (not targeted) to limit cost.
3. THE WAF_Logging configuration SHALL use S3 as the log destination rather than Kinesis Data Firehose, and THE S3 bucket SHALL have a lifecycle policy that deletes log objects older than 90 days.
4. WHERE the monthly WAF cost exceeds $50, THE monitoring configuration SHALL trigger a billing alert via AWS Budgets that sends a notification to the platform operator's configured email address or SNS topic.
5. THE WAF_Web_ACL configuration SHALL NOT include more than one paid Marketplace managed rule group.

### Requirement 11: Infrastructure as Code Definition

**User Story:** As a developer, I want the WAF configuration defined as Infrastructure as Code, so that it is version-controlled, reviewable, and reproducible across environments.

#### Acceptance Criteria

1. THE WAF_Web_ACL and all associated resources (IP_Sets, logging configuration, CloudWatch Alarms) SHALL be defined using AWS CloudFormation or Terraform.
2. THE infrastructure code SHALL support parameterized deployment to both dev and prod environments, with configurable parameters for rate thresholds, IP sets, geo block list, S3 bucket name, and alarm thresholds.
3. THE infrastructure code SHALL be stored in the project repository under a dedicated `infrastructure/waf/` directory.
4. WHEN deployed to the dev environment, THE Rate_Based_Rule thresholds SHALL use values at 50% of production thresholds for all rate-based rules.
5. THE infrastructure code SHALL require a successful plan/changeset validation before applying changes to any environment.

### Requirement 12: Deployment Integration

**User Story:** As a platform operator, I want WAF deployment integrated with the existing CI/CD pipeline, so that WAF changes are deployed consistently alongside application changes.

#### Acceptance Criteria

1. THE GitHub Actions deploy workflow SHALL include a step that applies WAF infrastructure changes before deploying application services, with a maximum step timeout of 10 minutes.
2. IF a WAF deployment fails, THEN THE deploy workflow SHALL halt, not proceed with application deployment, and report the failure in the workflow summary output.
3. THE deploy workflow SHALL support deploying WAF changes independently of application code changes via workflow dispatch, accepting an environment parameter (dev or prod) to target the deployment.
4. WHEN a WAF deployment step completes successfully, THE deploy workflow SHALL verify that the WAF_Web_ACL resource exists and is associated with the target ALB before proceeding to application deployment.
