# Requirements Document

## Introduction

This document defines the requirements for a comprehensive security and legal compliance package for the Recipe AI Finder application. The package brings the application into compliance with GDPR (EU), CCPA/CPRA (California), and LGPD (Brazil) by implementing account deletion, data export, privacy policy, consent management, audit logging, rate limiting, and security hardening. The application is a Spring Boot 4 backend with a Next.js frontend deployed on AWS (DynamoDB, S3, Cognito, ECS/Fargate).

## Glossary

- **Backend**: The Spring Boot 4 REST API server that handles authentication, recipe generation, and data management
- **Frontend**: The Next.js web application providing the user interface
- **Account_Deletion_Service**: The backend service responsible for managing the account deletion lifecycle including soft delete, cancellation, and permanent purge
- **Data_Export_Service**: The backend service responsible for generating user data exports in JSON and ZIP formats
- **Consent_Service**: The backend service responsible for recording, retrieving, and validating user consent records
- **Audit_Service**: The backend service responsible for recording compliance events to DynamoDB and CloudWatch
- **Rate_Limiter**: The backend filter that enforces request rate limits per authenticated user
- **Consent_Modal**: The frontend component that blocks application usage until required consents are granted
- **Account_Settings_Page**: The frontend page where users manage deletion, export, and consent preferences
- **AuditLog_Table**: A DynamoDB table storing compliance audit events with partition key `auditId` and GSI on `userId`
- **Consent_Table**: A DynamoDB table storing consent records with partition key `userId` and sort key `consentType`
- **Users_Table**: The existing DynamoDB table storing user profiles
- **Recipes_Table**: The existing DynamoDB table storing user recipes
- **S3_Bucket**: The existing S3 bucket storing recipe images
- **Cognito_User_Pool**: The AWS Cognito user pool managing authentication identities
- **Soft_Delete**: A deletion mode that marks the account as pending deletion with a 30-day grace period before permanent purge
- **Hard_Delete**: A deletion mode that immediately and permanently removes all user data from all stores
- **Grace_Period**: The 30-day window during which a soft-deleted account can be reactivated
- **Scheduled_Deletion_Job**: A daily backend process that identifies and executes overdue soft deletions

## Requirements

### Requirement 1: Account Soft Deletion

**User Story:** As a user, I want to schedule my account for deletion with a grace period, so that I can change my mind and recover my account within 30 days.

#### Acceptance Criteria

1. WHEN a user requests soft deletion, THE Account_Deletion_Service SHALL mark the account status as `PENDING_DELETION`, set a scheduled deletion date 30 days from the request time, and log an `ACCOUNT_DELETION_REQUESTED` audit event
2. WHILE an account is in `PENDING_DELETION` status, THE Backend SHALL reject requests to generate new recipes for that user with an HTTP 403 response and an error message indicating the account is pending deletion
3. WHILE an account is in `PENDING_DELETION` status, THE Backend SHALL continue to allow the user to authenticate and access read-only operations (view existing recipes, access account settings, request data export, and cancel the pending deletion)
4. WHILE an account is in `PENDING_DELETION` status AND the scheduled deletion date has not passed, THE Account_Deletion_Service SHALL allow the user to cancel the deletion and revert the account to `ACTIVE` status
5. WHEN a user cancels a pending deletion, THE Account_Deletion_Service SHALL revert the account status to `ACTIVE`, clear the scheduled deletion date, and log an `ACCOUNT_REACTIVATED` audit event
6. IF a user attempts to cancel a pending deletion after the scheduled deletion date has passed, THEN THE Account_Deletion_Service SHALL reject the cancellation with an error message indicating the grace period has expired
7. WHEN the Scheduled_Deletion_Job runs, THE Account_Deletion_Service SHALL identify all accounts with `PENDING_DELETION` status whose scheduled deletion date has passed and execute a hard deletion for each

### Requirement 2: Account Hard Deletion (Immediate Permanent Purge)

**User Story:** As a user, I want to immediately and permanently delete my account and all associated data, so that my personal data is irrecoverably removed from the system.

#### Acceptance Criteria

1. WHEN a user requests immediate hard deletion, THE Account_Deletion_Service SHALL permanently remove all user data from all stores and return an HTTP 200 response confirming deletion within 60 seconds of the request
2. WHEN executing a hard deletion, THE Account_Deletion_Service SHALL delete all recipes belonging to the user from the Recipes_Table
3. WHEN executing a hard deletion, THE Account_Deletion_Service SHALL delete all image objects belonging to the user from the S3_Bucket
4. WHEN executing a hard deletion, THE Account_Deletion_Service SHALL delete the user record from the Users_Table
5. WHEN executing a hard deletion, THE Account_Deletion_Service SHALL call the Cognito AdminDeleteUser API to remove the user from the Cognito_User_Pool
6. WHEN executing a hard deletion, THE Account_Deletion_Service SHALL log an `ACCOUNT_DELETION_COMPLETED` audit event including the userId and timestamp before removing the user record from the Users_Table, ensuring the audit record persists regardless of subsequent step outcomes
7. IF a partial failure occurs during hard deletion, THEN THE Account_Deletion_Service SHALL mark the account status as `DELETION_FAILED`, log the failure details including which store operation failed, and respond to the user with an error message indicating that deletion is incomplete and will be retried automatically
8. WHEN the Scheduled_Deletion_Job encounters an account in `DELETION_FAILED` status, THE Account_Deletion_Service SHALL retry the deletion starting from the first incomplete step
9. WHEN executing a hard deletion for a user who has zero recipes or zero images, THE Account_Deletion_Service SHALL skip the corresponding deletion steps and proceed to complete the remaining steps successfully

### Requirement 3: Scheduled Deletion Job

**User Story:** As a system operator, I want overdue soft-deleted accounts to be automatically purged daily, so that the system fulfills deletion commitments without manual intervention.

#### Acceptance Criteria

1. THE Scheduled_Deletion_Job SHALL execute once per day on a configurable cron schedule defaulting to 02:00 UTC daily
2. WHEN the Scheduled_Deletion_Job executes, THE Account_Deletion_Service SHALL query all users with `PENDING_DELETION` status and a scheduled deletion date earlier than the job execution start time, and execute a hard deletion (as defined in Requirement 2) for each identified account
3. THE Scheduled_Deletion_Job SHALL be idempotent, producing the same result regardless of how many times it runs for the same set of accounts
4. IF the Scheduled_Deletion_Job encounters an error processing one account, THEN THE Account_Deletion_Service SHALL log an audit event containing the affected userId and error details, leave the account in `PENDING_DELETION` status for retry on the next scheduled run, and continue processing remaining accounts
5. WHEN the Scheduled_Deletion_Job completes, THE Account_Deletion_Service SHALL log an audit event of type `SCHEDULED_DELETION_RUN` containing the total number of accounts processed, the number successfully deleted, and the number that failed

### Requirement 4: Data Export — JSON Format

**User Story:** As a user, I want to export all my personal data in a machine-readable JSON format, so that I can exercise my right to data portability under GDPR/CCPA/LGPD.

#### Acceptance Criteria

1. WHEN a user requests a JSON data export, THE Data_Export_Service SHALL return a synchronous JSON response within 30 seconds containing all user profile data and all recipe data associated with the authenticated user
2. THE Data_Export_Service SHALL include in the JSON export: user profile fields (email, username, createdAt), and for each recipe: title, description, ingredients, steps, model (BedrockModel used for text generation), imageModel (ImageModel used for image generation), textGenerationMs, imageGenerationMs, createdAt, and S3 object keys for associated images
3. WHEN a user requests a JSON export, THE Data_Export_Service SHALL log a `DATA_EXPORT_REQUESTED` audit event containing the userId and timestamp before generating the export
4. IF the Data_Export_Service encounters an error while generating the JSON export, THEN THE Data_Export_Service SHALL return an error response indicating the export failed and log a `DATA_EXPORT_FAILED` audit event
5. WHEN a user with no recipe records requests a JSON data export, THE Data_Export_Service SHALL return a valid JSON response containing the user profile fields and an empty recipe collection

### Requirement 5: Data Export — ZIP Format with Images

**User Story:** As a user, I want to export all my data including recipe images in a downloadable ZIP file, so that I have a complete portable copy of everything the system stores about me.

#### Acceptance Criteria

1. WHEN a user requests a ZIP data export, THE Data_Export_Service SHALL return an HTTP 202 Accepted response and begin asynchronous ZIP generation
2. WHEN generating the ZIP export, THE Data_Export_Service SHALL include a `data.json` file containing all user profile fields and all recipe records (matching the JSON export structure defined in Requirement 4), plus all recipe image files downloaded from the S3_Bucket
3. WHEN the ZIP generation is complete, THE Data_Export_Service SHALL upload the ZIP file to a temporary S3 location and make it available via a presigned URL valid for 60 minutes
4. THE Data_Export_Service SHALL provide a status endpoint that reports whether the ZIP export is still in progress, completed (with download URL), or failed (with an error indication describing the failure reason)
5. WHEN a user requests a ZIP export, THE Data_Export_Service SHALL log a `DATA_EXPORT_REQUESTED` audit event
6. WHEN the ZIP generation completes successfully, THE Data_Export_Service SHALL log a `DATA_EXPORT_COMPLETED` audit event
7. IF a user requests a ZIP export while a previous export is still in progress, THEN THE Data_Export_Service SHALL reject the request with an error indicating that an export is already in progress
8. IF an image file cannot be downloaded from S3 during ZIP generation, THEN THE Data_Export_Service SHALL skip the unavailable image, continue generating the ZIP with remaining files, and include a manifest entry in `data.json` listing the missing image references

### Requirement 6: Consent Management — Recording and Validation

**User Story:** As a user, I want to explicitly grant or revoke consent for specific data processing activities, so that I maintain control over how my data is used.

#### Acceptance Criteria

1. WHEN a user grants consent for a specific type, THE Consent_Service SHALL record the consent in the Consent_Table with the userId, consentType, granted status set to `true`, timestamp, consent version (string up to 20 characters), and IP address (IPv4 or IPv6 format)
2. WHEN a user revokes consent for a specific type, THE Consent_Service SHALL update the consent record by setting the granted status to `false` and recording the revocation timestamp, while preserving the original grant timestamp and consent version
3. THE Consent_Service SHALL support the following consent types: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`
4. WHEN a user attempts to generate a recipe, THE Backend SHALL verify that the user has a consent record for `AI_DATA_PROCESSING` with granted status equal to `true` before calling any third-party AI service
5. IF a user attempts to generate a recipe without a consent record for `AI_DATA_PROCESSING` with granted status equal to `true`, THEN THE Backend SHALL reject the request with HTTP 403 and an error message indicating that AI data processing consent is required
6. WHEN consent is granted or revoked, THE Consent_Service SHALL log the corresponding `CONSENT_GRANTED` or `CONSENT_REVOKED` audit event
7. IF a user attempts to grant or revoke consent for a type not in the supported consent types list, THEN THE Consent_Service SHALL reject the request with HTTP 400 and an error message indicating the unsupported consent type
8. WHEN a user grants consent for a type they have already granted, THE Consent_Service SHALL update the existing record with the new timestamp, consent version, and IP address without creating a duplicate entry

### Requirement 7: Consent Modal — First Login Blocking Flow

**User Story:** As a new user, I want to be presented with clear consent options on my first login, so that I can make informed choices about how my data is processed before using the application.

#### Acceptance Criteria

1. WHEN a user accesses a protected page and has no active consent records for all three required types (`TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`), THE Consent_Modal SHALL display a modal that cannot be dismissed via escape key, backdrop click, or browser back navigation, requiring consent before proceeding
2. THE Consent_Modal SHALL present individual checkboxes for: Terms of Service agreement, Privacy Policy acknowledgment, and AI Data Processing consent
3. THE Consent_Modal SHALL include links to the full Privacy Policy and Terms of Service pages
4. THE Consent_Modal SHALL enable the "Accept & Continue" button only when all three checkboxes (`TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`) are checked
5. WHEN the user clicks "Accept & Continue", THE Frontend SHALL submit consent grants for each checked type via the consent API and then allow access to protected pages
6. IF the consent API call fails when the user clicks "Accept & Continue", THEN THE Frontend SHALL display an inline error message indicating the submission failed and keep the modal open with checkbox states preserved, allowing the user to retry
7. WHILE required consents (`TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`) are not recorded, THE Frontend SHALL prevent navigation to any protected page

### Requirement 8: Audit Logging

**User Story:** As a system operator, I want all compliance-relevant events to be recorded in a durable audit trail, so that the system can demonstrate compliance to regulators and support incident investigation.

#### Acceptance Criteria

1. WHEN a compliance event occurs, THE Audit_Service SHALL persist an audit record to the AuditLog_Table containing: auditId (unique identifier), userId, eventType, details map (maximum 10 key-value pairs, each key maximum 64 characters, each value maximum 1024 characters), timestamp in ISO-8601 UTC format, IP address, and user agent
2. WHEN a compliance event occurs, THE Audit_Service SHALL emit a structured JSON log entry to CloudWatch containing the same fields as the AuditLog_Table record (auditId, userId, eventType, details, timestamp, IP address, user agent)
3. THE Audit_Service SHALL record events for the following types: `ACCOUNT_DELETION_REQUESTED`, `ACCOUNT_DELETION_COMPLETED`, `ACCOUNT_REACTIVATED`, `DATA_EXPORT_REQUESTED`, `DATA_EXPORT_COMPLETED`, `CONSENT_GRANTED`, `CONSENT_REVOKED`
4. THE AuditLog_Table SHALL support querying audit records by userId via a Global Secondary Index with results sorted by timestamp in descending order (most recent first)
5. IF the Audit_Service fails to persist an audit record to the AuditLog_Table, THEN THE Audit_Service SHALL retry the write up to 3 times with exponential backoff, and if all retries fail, SHALL log the failure and the original event payload to CloudWatch as a critical error and throw an exception to prevent the triggering operation from completing silently without an audit trail
6. THE Audit_Service SHALL NOT provide any API or mechanism to update or delete existing audit records once persisted

### Requirement 9: Rate Limiting

**User Story:** As a system operator, I want to enforce request rate limits per user, so that the system is protected from abuse and compliance-sensitive endpoints are not overloaded.

#### Acceptance Criteria

1. THE Rate_Limiter SHALL enforce a maximum of 60 requests per minute per authenticated user on all API endpoints except those classified as sensitive (account deletion and data export endpoints)
2. THE Rate_Limiter SHALL enforce a maximum of 5 requests per hour per authenticated user on account deletion endpoints, counted independently from the general endpoint limit
3. WHEN a user exceeds the rate limit for any endpoint category, THE Rate_Limiter SHALL reject the request before processing, respond with HTTP 429 (Too Many Requests), and include a `Retry-After` header indicating the number of whole seconds until the next request is allowed based on a sliding window
4. THE Rate_Limiter SHALL maintain independent rate counters per authenticated user per endpoint category (general, account deletion, data export), so that one user's activity does not affect another user's allowance and exhausting one category does not block requests to a different category
5. THE Rate_Limiter SHALL enforce a maximum of 1 data export request (JSON or ZIP) per hour per authenticated user, counted independently from both the general and account deletion limits
6. IF a request arrives from an unauthenticated client, THEN THE Rate_Limiter SHALL enforce a maximum of 20 requests per minute per source IP address on public endpoints and respond with HTTP 429 and a `Retry-After` header when exceeded

### Requirement 10: Privacy Policy Page

**User Story:** As a user or visitor, I want to read a comprehensive privacy policy, so that I understand what data is collected, how it is processed, and what rights I have.

#### Acceptance Criteria

1. THE Frontend SHALL serve a publicly accessible privacy policy page at the `/privacy` route without requiring authentication
2. THE Privacy Policy page SHALL disclose the following categories of personal data collected: account data (email, username, account creation date), user-generated content (recipe titles, descriptions, ingredients, steps), AI-generated content (recipe images, model metadata), and technical data (IP address, user agent, consent timestamps)
3. THE Privacy Policy page SHALL disclose the following purposes of processing: account management, AI-powered recipe generation via third-party processors (AWS Bedrock, Stability AI, OpenAI, Google Imagen), recipe image generation and storage, consent record-keeping, and compliance audit logging
4. THE Privacy Policy page SHALL disclose data retention periods: 90-day image lifecycle for S3-stored images, 30-day retention for CloudWatch logs, and indefinite retention for account and recipe data until user-initiated deletion
5. THE Privacy Policy page SHALL disclose user rights including: access to personal data, rectification of inaccurate data, erasure (account deletion), data portability (JSON and ZIP export), and objection to processing (consent revocation)
6. THE Privacy Policy page SHALL include placeholder sections for controller contact information and Data Protection Officer contact details
7. THE Privacy Policy page SHALL display a last-updated date in ISO 8601 format (YYYY-MM-DD) and a version identifier
8. THE Frontend SHALL include a link to the Privacy Policy page in the footer of all layouts (public and protected)

### Requirement 11: Terms of Service Page

**User Story:** As a user or visitor, I want to read the terms of service, so that I understand the rules governing use of the application.

#### Acceptance Criteria

1. THE Frontend SHALL serve a publicly accessible Terms of Service page at the `/terms` route without requiring authentication, rendering the page content within 3 seconds on standard broadband connections
2. THE Terms of Service page SHALL display a last-updated date in ISO 8601 format (YYYY-MM-DD) and a version number in semantic format (e.g., "1.0")
3. THE Terms of Service page SHALL disclose: acceptable use policy, user responsibilities, intellectual property rights for AI-generated content, limitation of liability, account termination conditions, and governing jurisdiction placeholder
4. THE Frontend SHALL include a link to the Terms of Service page in the footer of all layouts (public and protected), with the link labeled "Terms of Service" and navigating to the `/terms` route
5. IF a user navigates to the `/terms` route while the page content fails to load, THEN THE Frontend SHALL display an error message indicating the content is temporarily unavailable

### Requirement 12: Account Settings Page

**User Story:** As a user, I want a dedicated settings page where I can manage my account lifecycle, data exports, and consent preferences in one place.

#### Acceptance Criteria

1. THE Account_Settings_Page SHALL require authentication and redirect unauthenticated users to the login page
2. THE Account_Settings_Page SHALL display the user's profile information: email, username, and account creation date formatted as a locale-appropriate date string
3. THE Account_Settings_Page SHALL provide a control to initiate a JSON data export that triggers an immediate file download upon completion
4. THE Account_Settings_Page SHALL provide a control to initiate a ZIP data export and display the export status as one of: "in progress", "completed" (with a download link to the presigned URL), or "failed"
5. THE Account_Settings_Page SHALL provide controls to request soft deletion (with visible text explaining that the account will be permanently deleted after 30 days and can be cancelled during that period) and immediate hard deletion
6. WHEN a user selects immediate hard deletion, THE Account_Settings_Page SHALL display a confirmation dialog requiring the user to type the exact case-sensitive phrase "DELETE" before the submit action becomes enabled
7. WHILE an account is in `PENDING_DELETION` status, THE Frontend SHALL display a persistent banner on all protected pages showing the scheduled permanent deletion date and providing a cancel button that reverts the account to `ACTIVE` status
8. THE Account_Settings_Page SHALL display the current granted/revoked status of each consent type (`TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `AI_DATA_PROCESSING`) and allow revocation only of the `AI_DATA_PROCESSING` consent, which is optional
9. WHEN a user initiates revocation of `AI_DATA_PROCESSING` consent, THE Account_Settings_Page SHALL display a warning indicating that recipe generation will be unavailable until consent is re-granted, and require explicit confirmation before submitting the revocation
10. THE Frontend SHALL include a navigation link to the Account_Settings_Page in the authenticated user menu

### Requirement 13: Security Hardening — Input Validation

**User Story:** As a system operator, I want all API inputs to be validated against strict constraints, so that the system is protected from malformed or malicious payloads.

#### Acceptance Criteria

1. THE Backend SHALL validate all request body fields using Bean Validation constraints (maximum lengths, allowed patterns, required fields) on all controller endpoints, and SHALL validate path parameters and query parameters using constraint annotations (e.g., `@Pattern`, `@Size`)
2. WHEN a request contains invalid input, THE Backend SHALL respond with HTTP 400 (Bad Request) and a JSON error response containing the HTTP status code, a timestamp, and at least one field-level error message identifying the invalid field name and the constraint that was violated
3. THE Backend SHALL enforce a maximum request body size of 1 MB; WHEN a request exceeds this limit, THE Backend SHALL respond with HTTP 413 (Payload Too Large)
4. THE Backend SHALL annotate all request DTOs with `@JsonIgnoreProperties(ignoreUnknown = true)` to prevent mass-assignment of unexpected fields
5. WHEN a request body cannot be parsed as valid JSON, THE Backend SHALL respond with HTTP 400 (Bad Request) and an error message indicating a malformed request body

### Requirement 14: Security Hardening — CORS and Headers

**User Story:** As a system operator, I want the application to enforce strict CORS policies and include security headers, so that the system is hardened against common web vulnerabilities.

#### Acceptance Criteria

1. THE Backend SHALL restrict CORS allowed origins to explicitly configured domain values only, and SHALL reject any configuration that contains a wildcard (`*`) origin when the application is running with a production profile
2. THE Backend SHALL restrict CORS allowed headers to the following allowlist: `Authorization`, `Content-Type`, `Accept`, `Origin`, `X-Requested-With`, and `Cache-Control`, rejecting preflight requests that include headers outside this list
3. THE Backend SHALL include the following security headers in all HTTP responses: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security` with a `max-age` value of at least 31536000 seconds and the `includeSubDomains` directive, and a `Content-Security-Policy` header with at minimum `default-src 'self'` and `frame-ancestors 'none'` directives
4. THE Backend SHALL expose CORS configuration via externalized properties so that allowed origins can differ between environments without code changes
5. IF a cross-origin request arrives from an origin not present in the configured allowed origins list, THEN THE Backend SHALL omit CORS response headers, causing the browser to block the response on the client side

### Requirement 15: Infrastructure — New DynamoDB Tables

**User Story:** As a system operator, I want dedicated DynamoDB tables for audit logs and consent records, so that compliance data is stored in purpose-built, queryable tables.

#### Acceptance Criteria

1. THE Infrastructure SHALL provision an AuditLog_Table with partition key `auditId` (String), a GSI on `userId` with sort key `timestamp` (String, ISO-8601) projecting all attributes, a TTL attribute set to expire records after 90 days by default, and on-demand billing mode
2. THE Infrastructure SHALL provision a Consent_Table with partition key `userId` (String), sort key `consentType` (String), and on-demand billing mode
3. THE Infrastructure SHALL grant the ECS task role IAM permissions for `cognito-idp:AdminDeleteUser` and `cognito-idp:AdminDisableUser` scoped to the specific Cognito_User_Pool resource ARN
4. THE Infrastructure SHALL grant the ECS task role IAM permissions for `dynamodb:PutItem`, `dynamodb:GetItem`, `dynamodb:Query`, and `dynamodb:DeleteItem` on the AuditLog_Table and Consent_Table, scoped to those table ARNs and their indexes

### Requirement 16: Integration Testing — Compliance Flow

**User Story:** As a developer, I want comprehensive integration tests covering the full compliance lifecycle, so that regressions in compliance behavior are detected automatically.

#### Acceptance Criteria

1. THE Integration_Tests SHALL verify the full account lifecycle in sequence: account creation, consent grant, recipe generation (as app usage), soft deletion request, cancellation and reactivation, immediate hard deletion, and confirmation that the user record is absent from the Users_Table, Recipes_Table, S3_Bucket, Cognito_User_Pool, and Consent_Table
2. THE Integration_Tests SHALL verify that a recipe generation request made without active `AI_DATA_PROCESSING` consent is rejected with an HTTP 403 response and an error message indicating that consent is required
3. THE Integration_Tests SHALL verify that rate limiting returns HTTP 429 with a `Retry-After` header when the per-user limit is exceeded on account deletion and data export endpoints
4. THE Integration_Tests SHALL verify that audit log entries are persisted to the AuditLog_Table for the following event types: `ACCOUNT_DELETION_REQUESTED`, `ACCOUNT_DELETION_COMPLETED`, `ACCOUNT_REACTIVATED`, `DATA_EXPORT_REQUESTED`, `DATA_EXPORT_COMPLETED`, `CONSENT_GRANTED`, and `CONSENT_REVOKED`
5. THE Integration_Tests SHALL verify that a JSON data export response contains the user profile fields (email, username, createdAt), all recipe records (title, description, ingredients, steps, model metadata, creation date), and S3 object keys for associated images
6. THE Integration_Tests SHALL verify that revoking `AI_DATA_PROCESSING` consent causes subsequent recipe generation requests to be rejected with an HTTP 403 response
7. IF a partial failure occurs during a hard deletion test scenario, THEN THE Integration_Tests SHALL verify that the failure is logged, the partially-deleted state is recorded, and a retry on the next scheduled run completes the deletion
