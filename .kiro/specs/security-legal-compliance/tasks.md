# Implementation Plan: Security & Legal Compliance

## Overview

This plan implements a comprehensive GDPR/CCPA/LGPD compliance package for the Recipe AI Finder application. The implementation follows a bottom-up dependency order: infrastructure and models first, then services, then controllers, then frontend, then tests. The backend uses Java 21 with Spring Boot 4 and DynamoDB Enhanced Client. The frontend uses TypeScript with Next.js.

## Tasks

- [x] 1. Add new dependencies and create enums and models
  - [x] 1.1 Add compliance dependencies to pom.xml and create enums
    - Add `bucket4j-core` dependency to `pom.xml` for rate limiting
    - Add `cognitoidentityprovider` AWS SDK dependency to `pom.xml`
    - Add `jqwik` test dependency for property-based testing
    - Create `AccountStatus` enum: `ACTIVE`, `PENDING_DELETION`, `DELETION_FAILED`
    - Create `ConsentType` enum: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`
    - Create `AuditEventType` enum with all event types
    - Create `RateLimitCategory` enum: `GENERAL`, `DELETION`, `EXPORT`
    - _Requirements: 1.1, 2.7, 6.3, 6.7, 8.3, 9.1, 9.2, 9.5_

  - [x] 1.2 Extend the User model and create Consent and AuditEvent models
    - Add `accountStatus`, `deletionRequestedAt`, `scheduledDeletionDate` fields to `User.java`
    - Create `Consent` model with DynamoDB annotations (partition key: userId, sort key: consentType)
    - Create `AuditEvent` model with DynamoDB annotations (partition key: auditId, GSI: userId+timestamp)
    - _Requirements: 1.1, 6.1, 8.1, 15.1, 15.2_

  - [x] 1.3 Create request/response DTOs for compliance endpoints
    - Create `DeleteAccountRequest` DTO with `type` field (soft/immediate) and validation constraints
    - Create `GrantConsentRequest` DTO with `consentType`, `version`, validation constraints
    - Create `DataExportJson` response class for JSON export structure
    - Create `ExportStatusResponse` DTO for ZIP export status
    - Annotate all DTOs with `@JsonIgnoreProperties(ignoreUnknown = true)`
    - _Requirements: 2.1, 4.1, 5.4, 6.1, 13.4_

- [x] 2. Implement repositories
  - [x] 2.1 Create ConsentRepository
    - Create `ConsentRepository` using DynamoDB Enhanced Client
    - Implement `save(Consent)`, `findByUserIdAndType(userId, consentType)`, `findAllByUserId(userId)`, `delete(userId, consentType)`
    - Wire DynamoDB table name from `application.properties`
    - _Requirements: 6.1, 6.2, 6.8, 15.2_

  - [x] 2.2 Create AuditRepository
    - Create `AuditRepository` using DynamoDB Enhanced Client
    - Implement `save(AuditEvent)`, `findByUserId(userId)` using the GSI sorted by timestamp descending
    - Wire DynamoDB table name from `application.properties`
    - _Requirements: 8.1, 8.4, 15.1_

- [x] 3. Implement core services
  - [x] 3.1 Implement AuditService with dual-write and retry logic
    - Create `AuditService` with `logEvent(userId, eventType, details, ipAddress, userAgent)` method
    - Implement DynamoDB write with 3 retries and exponential backoff (100ms, 200ms, 400ms base)
    - Emit structured JSON log to a dedicated SLF4J logger for CloudWatch
    - Set TTL on audit records to 90 days from creation
    - Throw exception if all DynamoDB retries fail (prevent silent completion)
    - CloudWatch log failure must NOT block DynamoDB write
    - _Requirements: 8.1, 8.2, 8.5, 8.6_

  - [x] 3.2 Write property test for AuditService (Property 12: Audit dual-write completeness)
    - **Property 12: Audit record dual-write completeness**
    - **Validates: Requirements 8.1, 8.2, 8.5**
    - Use jqwik to generate random audit events with varying details maps
    - Verify both DynamoDB write and CloudWatch structured log occur for each event
    - Verify exception is thrown when DynamoDB write fails after 3 retries

  - [x] 3.3 Implement ConsentService
    - Create `ConsentService` with methods: `grantConsent`, `revokeConsent`, `getConsents`, `hasActiveConsent`, `hasAllRequiredConsents`
    - Grant: upsert consent record (granted=true, new timestamp, version, IP)
    - Revoke: set granted=false, record revocation timestamp, preserve original grant fields
    - Validate consent type against `ConsentType` enum; reject invalid types with IllegalArgumentException
    - Log `CONSENT_GRANTED` / `CONSENT_REVOKED` audit events via AuditService
    - _Requirements: 6.1, 6.2, 6.4, 6.5, 6.6, 6.7, 6.8_

  - [x] 3.4 Write property tests for ConsentService (Properties 9, 10, 11)
    - **Property 9: Consent grant round-trip and idempotence**
    - **Property 10: Consent gates recipe generation**
    - **Property 11: Invalid consent type rejection**
    - **Validates: Requirements 6.1, 6.2, 6.4, 6.5, 6.7, 6.8**
    - Use jqwik to test: grant/revoke sequences preserve state correctly, duplicate grants don't create duplicates, invalid types rejected

  - [x] 3.5 Implement AccountDeletionService
    - Create `AccountDeletionService` with methods: `requestSoftDeletion`, `cancelDeletion`, `executeHardDeletion`, `processPendingDeletions`
    - Soft deletion: set status to PENDING_DELETION, scheduledDeletionDate = now + 30 days, log audit
    - Cancel: revert to ACTIVE if scheduledDeletionDate is in the future, log ACCOUNT_REACTIVATED
    - Hard deletion: delete all recipes, delete S3 images, delete user record, call Cognito AdminDeleteUser, log ACCOUNT_DELETION_COMPLETED before user record deletion
    - Handle partial failures: set DELETION_FAILED, log which step failed
    - Add `CognitoIdentityProviderClient` bean to `AwsConfig`
    - Add `@Scheduled` daily cron job for processing overdue pending deletions
    - Scheduled job: log `SCHEDULED_DELETION_RUN` summary audit event
    - _Requirements: 1.1, 1.4, 1.5, 1.6, 1.7, 2.1–2.9, 3.1–3.5_

  - [x] 3.6 Write property tests for AccountDeletionService (Properties 1, 3, 4, 5)
    - **Property 1: Soft deletion state transition correctness**
    - **Property 3: Hard deletion completeness**
    - **Property 4: Partial deletion failure handling**
    - **Property 5: Scheduled deletion job filtering and idempotence**
    - **Validates: Requirements 1.1, 1.4, 1.5, 1.7, 2.1–2.9, 3.2, 3.3, 3.4**
    - Use jqwik with mocked AWS clients to verify all state transitions and data purge completeness

  - [x] 3.7 Implement DataExportService
    - Create `DataExportService` with methods: `exportJson`, `startZipExport`, `getExportStatus`
    - JSON export: synchronous, gather user profile + all recipes, return within 30 seconds
    - ZIP export: use `@Async`, include data.json + images from S3, upload ZIP to temp S3 location, return presigned URL valid 60 minutes
    - Track in-progress exports per user, reject duplicate ZIP requests
    - Handle missing S3 images: skip and list in manifest
    - Log `DATA_EXPORT_REQUESTED` and `DATA_EXPORT_COMPLETED` audit events
    - _Requirements: 4.1–4.5, 5.1–5.8_

  - [x] 3.8 Write property tests for DataExportService (Properties 6, 7, 8)
    - **Property 6: Data export completeness (JSON)**
    - **Property 7: ZIP export content completeness**
    - **Property 8: Duplicate export prevention**
    - **Validates: Requirements 4.1, 4.2, 4.5, 5.2, 5.7, 5.8**
    - Use jqwik to generate users with 0-20 recipes and verify export content matches

- [ ] 4. Checkpoint - Ensure all service tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4.5. Implement Terraform infrastructure changes
  - [ ] 4.5.1 Add Consent and AuditLog DynamoDB tables to `modules/dynamodb`
    - Add `aws_dynamodb_table.consent` resource with partition key `userId` (S), sort key `consentType` (S), on-demand billing, and environment tags
    - Add `aws_dynamodb_table.audit_log` resource with partition key `auditId` (S), GSI `userId-timestamp-index` (hash: `userId`, range: `timestamp`, ALL projection), TTL on `ttl` attribute, on-demand billing, and environment tags
    - Add outputs: `consent_table_name`, `consent_table_arn`, `audit_log_table_name`, `audit_log_table_arn`
    - _Requirements: 15.1, 15.2, 15.9_

  - [ ] 4.5.2 Add Cognito admin permissions to ECS task role in `modules/iam`
    - Add IAM policy statement granting `cognito-idp:AdminDeleteUser` and `cognito-idp:AdminDisableUser` scoped to the Cognito User Pool ARN
    - Add `cognito_user_pool_arn` variable to the IAM module
    - Pass `cognito_user_pool_arn` from the `cognito` module output through root `main.tf`
    - _Requirements: 15.3_

  - [ ] 4.5.3 Wire new environment variables into ECS backend task definition
    - Add `dynamodb_consent_table`, `dynamodb_audit_table`, `cognito_user_pool_id` variables to `modules/ecs`
    - Add `DYNAMODB_CONSENT_TABLE`, `DYNAMODB_AUDIT_TABLE`, `COGNITO_USER_POOL_ID` environment entries to the backend container definition
    - _Requirements: 15.5, 15.6, 15.7_

  - [ ] 4.5.4 Update root `main.tf` to wire module outputs
    - Pass `dynamodb_consent_table` and `dynamodb_audit_table` from `module.dynamodb` to `module.ecs`
    - Pass `cognito_user_pool_id` from `module.cognito` to `module.ecs`
    - Pass `cognito_user_pool_arn` from `module.cognito` to `module.iam`
    - Ensure `modules/cognito` exports `user_pool_id` and `user_pool_arn` (add outputs if missing)
    - _Requirements: 15.8_

  - [ ] 4.5.5 Validate Terraform plan
    - Run `terraform plan` and verify only expected resources are created/modified (2 new DynamoDB tables, IAM policy update, ECS task definition update)
    - Confirm no destructive changes to existing resources
    - _Requirements: 15.1–15.9_

- [ ] 5. Implement filters and security hardening
  - [ ] 5.1 Implement RateLimitFilter with per-user token bucket
    - Create `RateLimitFilter` extending `OncePerRequestFilter`
    - Implement per-user token buckets using Bucket4j: GENERAL (60/min), DELETION (5/hr), EXPORT (1/hr)
    - Implement per-IP bucket for unauthenticated requests (20/min)
    - Map endpoint paths to rate limit categories
    - Return HTTP 429 with `Retry-After` header when exceeded
    - Extract userId from JWT; if extraction fails, treat as unauthenticated
    - Register filter in the Spring Security filter chain
    - _Requirements: 9.1–9.6_

  - [ ] 5.2 Write property tests for RateLimitFilter (Properties 13, 14)
    - **Property 13: Rate limiter per-user isolation**
    - **Property 14: Rate limiter threshold enforcement and response format**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5**
    - Use jqwik to verify isolation between users/categories and threshold enforcement

  - [ ] 5.3 Add security headers and tighten CORS configuration
    - Add security headers to SecurityConfig: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security` (max-age 31536000, includeSubDomains), `Content-Security-Policy` (default-src 'self', frame-ancestors 'none')
    - Tighten CORS `allowedHeaders` to explicit allowlist: `Authorization`, `Content-Type`, `Accept`, `Origin`, `X-Requested-With`, `Cache-Control`
    - Add production-profile validation that rejects wildcard origins
    - Add max request body size limit (1 MB) via `server.tomcat.max-http-form-post-size` and a filter
    - _Requirements: 14.1–14.5_

  - [ ] 5.4 Write property tests for security headers and CORS (Properties 16, 17)
    - **Property 16: Security headers presence**
    - **Property 17: CORS rejection for non-allowed origins**
    - **Validates: Requirements 14.3, 14.5**
    - Use jqwik to verify headers present on all responses and CORS rejection for invalid origins

  - [ ] 5.5 Add input validation to all request DTOs and controllers
    - Add Bean Validation constraints (`@Size`, `@Pattern`, `@NotNull`, `@NotBlank`) to all existing and new DTOs
    - Ensure `GlobalExceptionHandler` returns structured error response with field name and constraint violated
    - Handle malformed JSON (return 400), oversized payloads (return 413)
    - Add `@Validated` annotation to all controllers
    - _Requirements: 13.1–13.5_

  - [ ] 5.6 Write property test for input validation (Property 15)
    - **Property 15: Input validation rejects invalid payloads**
    - **Validates: Requirements 13.1, 13.2, 13.3, 13.5**
    - Use jqwik to generate invalid payloads and verify proper rejection

- [ ] 6. Implement controllers
  - [ ] 6.1 Create AccountController
    - Create `AccountController` at `/api/account`
    - Endpoints: `POST /delete` (soft or immediate), `POST /cancel-deletion`, `GET /export?format=json`, `POST /export?format=zip`, `GET /export/status`, `GET /profile`
    - Add consent check to `RecipeController.generateRecipes` (verify AI_DATA_PROCESSING consent)
    - Add PENDING_DELETION check to `RecipeController.generateRecipes` (return 403)
    - _Requirements: 1.2, 1.3, 2.1, 4.1, 5.1, 5.4, 6.4, 6.5, 12.1_

  - [ ] 6.2 Create ConsentController
    - Create `ConsentController` at `/api/consent`
    - Endpoints: `POST /` (grant), `DELETE /{type}` (revoke), `GET /` (list all user consents)
    - Validate consent type in path variable
    - Extract IP address from request for consent records
    - _Requirements: 6.1, 6.2, 6.3, 6.7_

  - [ ] 6.3 Update SecurityConfig for new endpoints
    - Add `/api/consent`, `/api/account/**` to authenticated routes
    - Add `/privacy`, `/terms` to public routes (if backend serves static content)
    - Ensure the new controllers are accessible through the security filter chain
    - Wire RateLimitFilter before authentication in the filter chain
    - _Requirements: 9.6, 10.1, 11.1_

  - [ ] 6.4 Write property test for pending deletion blocking writes (Property 2)
    - **Property 2: Pending deletion blocks write operations**
    - **Validates: Requirements 1.2, 1.3**
    - Use jqwik to verify PENDING_DELETION users get 403 on recipe generation but can still read

- [ ] 7. Checkpoint - Ensure all backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Implement frontend - consent and legal pages
  - [ ] 8.1 Create API client functions for compliance endpoints
    - Add API functions for: consent grant/revoke/list, account delete/cancel, export JSON/ZIP/status, profile fetch
    - Follow existing patterns in the `lib/` directory
    - _Requirements: 4.1, 5.1, 6.1, 6.2, 12.3, 12.4_

  - [ ] 8.2 Create Privacy Policy page at `/privacy`
    - Create `/app/(auth)/privacy/page.tsx` (or a public route group)
    - Include all required disclosures: data collected, purposes, third-party processors, retention periods, user rights, DPO placeholder
    - Display last-updated date (ISO 8601) and version identifier
    - Make publicly accessible without authentication
    - _Requirements: 10.1–10.7_

  - [ ] 8.3 Create Terms of Service page at `/terms`
    - Create `/app/(auth)/terms/page.tsx` (or a public route group)
    - Include: acceptable use, responsibilities, IP rights, liability, termination, jurisdiction placeholder
    - Display last-updated date (ISO 8601) and semantic version
    - Make publicly accessible without authentication
    - _Requirements: 11.1–11.5_

  - [ ] 8.4 Add footer links to Privacy Policy and Terms of Service
    - Add links to `/privacy` and `/terms` in the footer of both public and protected layouts
    - Ensure links are labeled "Privacy Policy" and "Terms of Service"
    - _Requirements: 10.8, 11.4_

- [ ] 9. Implement frontend - consent modal and account settings
  - [ ] 9.1 Create ConsentModal component
    - Create blocking overlay modal in the protected layout
    - Three checkboxes: ToS, Privacy Policy, AI Data Processing
    - Links to `/privacy` and `/terms` pages
    - "Accept & Continue" button enabled only when all three checked
    - Cannot be dismissed via escape, backdrop click, or back navigation
    - On submit: call consent API for each type, then remove modal
    - Show inline error if API call fails; keep modal open with preserved state
    - Check consent status on protected layout mount; show modal if any required consent missing
    - _Requirements: 7.1–7.7_

  - [ ] 9.2 Create Account Settings page at `/app/(protected)/account/page.tsx`
    - Profile section: display email, username, createdAt
    - Consent management: show granted/revoked status for each type; allow revocation of AI_DATA_PROCESSING with warning
    - Data export section: JSON download button, ZIP export with status indicator
    - Account deletion section: soft delete with explanation, immediate delete with "DELETE" confirmation dialog
    - Add navigation link in the authenticated user menu
    - _Requirements: 12.1–12.10_

  - [ ] 9.3 Create PendingDeletionBanner component
    - Persistent banner in protected layout when account status is PENDING_DELETION
    - Show scheduled permanent deletion date
    - Provide cancel button that calls cancel-deletion endpoint
    - _Requirements: 12.7_

- [ ] 10. Checkpoint - Ensure frontend and backend are wired together
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 11. Integration tests
  - [ ] 11.1 Write integration tests for full compliance lifecycle
    - Test full account lifecycle: create → consent → use → soft delete → cancel → hard delete → verify clean
    - Test consent flow blocks recipe generation
    - Test rate limiting returns 429 with Retry-After header
    - Test audit log entries for all compliance event types
    - Test JSON export contains all expected fields
    - Test revocation of AI_DATA_PROCESSING blocks subsequent recipe generation
    - Test partial deletion failure → DELETION_FAILED → retry completes
    - Use Spring Boot test with mocked AWS clients
    - _Requirements: 16.1–16.7_

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document (17 properties using jqwik)
- Unit tests validate specific examples and edge cases
- Backend uses Java 21 with Spring Boot 4, DynamoDB Enhanced Client, AWS SDK v2
- Frontend uses TypeScript with Next.js (App Router)
- All AWS clients (DynamoDB, S3, Cognito) should be mocked in tests

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1", "2.2"] },
    { "id": 3, "tasks": ["3.1", "3.3"] },
    { "id": 4, "tasks": ["3.2", "3.4", "3.5", "3.7"] },
    { "id": 5, "tasks": ["3.6", "3.8", "5.1", "5.3", "5.5"] },
    { "id": 6, "tasks": ["4.5.1", "4.5.2", "5.2", "5.4", "5.6", "6.1", "6.2", "6.3"] },
    { "id": 7, "tasks": ["4.5.3", "4.5.4", "6.4", "8.1"] },
    { "id": 8, "tasks": ["4.5.5", "8.2", "8.3"] },
    { "id": 9, "tasks": ["8.4", "9.1", "9.2", "9.3"] },
    { "id": 10, "tasks": ["11.1"] }
  ]
}
```
