variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "project_name" {
  type    = string
  default = "recipe-ai"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "domain_name" {
  type        = string
  description = "Your registered domain, e.g. example.com"
}

variable "cognito_google_client_id_ssm" {
  type        = string
  description = "SSM Parameter Store path for Google OAuth client ID"
}

variable "cognito_google_client_secret_ssm" {
  type        = string
  description = "SSM Parameter Store path for Google OAuth client secret"
}

variable "stability_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Stability AI API key"
}

variable "openai_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the OpenAI API key"
}

variable "google_api_key_arn" {
  type        = string
  description = "Secrets Manager ARN for the Google AI Studio API key"
}

# --- WAF Variables ---

variable "waf_allowed_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the WAF IP allow-list"
}

variable "waf_allowed_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the WAF IP allow-list"
}

variable "waf_blocked_ips" {
  type        = list(string)
  default     = []
  description = "IPv4 CIDR ranges for the WAF IP block-list"
}

variable "waf_blocked_ips_v6" {
  type        = list(string)
  default     = []
  description = "IPv6 CIDR ranges for the WAF IP block-list"
}

variable "waf_geo_block_countries" {
  type        = list(string)
  default     = []
  description = "ISO 3166-1 alpha-2 country codes to block"
}

variable "waf_rate_limit_global" {
  type        = number
  default     = 2000
  description = "Global requests per 5-min window per IP"
}

variable "waf_rate_limit_recipe_gen" {
  type        = number
  default     = 100
  description = "Recipe generation requests per 5-min window per IP"
}

variable "waf_rate_limit_image_upload" {
  type        = number
  default     = 60
  description = "Image upload requests per 5-min window per IP"
}

variable "waf_rate_limit_auth" {
  type        = number
  default     = 30
  description = "Auth endpoint requests per 5-min window per IP"
}

variable "waf_alarm_sns_topic_arn" {
  type        = string
  default     = ""
  description = "SNS topic ARN for WAF CloudWatch alarms (empty = no alarm actions)"
}

variable "waf_blocked_requests_alarm_threshold" {
  type        = number
  default     = 1000
  description = "Blocked requests in 5-min period to trigger alarm"
}

variable "waf_budget_limit_amount" {
  type        = string
  default     = "50"
  description = "Monthly WAF budget limit in USD"
}

variable "waf_budget_notification_email" {
  type        = string
  default     = ""
  description = "Email for budget alerts (empty = no budget resource created)"
}

# --- CloudWatch monitoring suite (opt-in) ---

variable "enable_monitoring" {
  type        = bool
  default     = false
  description = "Provision the CloudWatch monitoring suite (SNS topic, alarms incl. NAT gateway, dashboard, budget) and wire the existing WAF alarm to the new SNS topic. Off by default (no extra cost)."
}

variable "monitoring_notification_email" {
  type        = string
  default     = ""
  description = "Email for monitoring alarm notifications + the CloudWatch budget. Required when enable_monitoring=true (module fails fast otherwise). Confirmation is a one-time manual click."
}

variable "monitoring_budget_limit_amount" {
  type        = string
  default     = "20"
  description = "Monthly budget (USD) scoped to CloudWatch. Design §9 estimates ~$5–15/mo."
}

variable "enable_xray" {
  type        = bool
  default     = false
  description = "Reserved for the optional X-Ray tracing layer (Milestone/Task 9). Off by default; not yet wired."
}

# --- OpenSearch catalog backend (opt-in) ---

variable "enable_opensearch" {
  type        = bool
  default     = false
  description = "Provision OpenSearch Serverless (NextGen) for catalog search. Off by default (no extra cost)."
}

variable "enable_catalog_full" {
  type        = bool
  default     = false
  description = "Create the full 2.2M catalog DynamoDB table used by the OpenSearch backend."
}

variable "enable_batch_embedding" {
  type        = bool
  default     = false
  description = "Provision S3 buckets + Bedrock batch service role for the full 2.2M batch embedding load (Task 10.3). Not needed for the small-catalog reindex validation."
}

variable "catalog_search_backend" {
  type        = string
  default     = "inapp"
  description = "Catalog search backend the backend service uses: inapp | opensearch."
}

variable "catalog_search_mode" {
  type        = string
  default     = "hybrid"
  description = "Catalog search mode: keyword | semantic | hybrid."
}

variable "catalog_semantic_enabled" {
  type        = bool
  default     = true
  description = "Embed queries for semantic (k-NN) ranking."
}

variable "opensearch_knn_ef_search" {
  type        = number
  default     = 100
  description = "k-NN ef_search: recall/latency tuning for OpenSearch semantic queries. Raise for better recall at higher latency."
}

variable "opensearch_knn_quantization" {
  type        = string
  default     = "none"
  description = "OpenSearch vector quantization: none | fp16 | byte. Must match the quantization the reindex built the index with (fp16 for the full 2.2M load)."
}

variable "opensearch_admin_principals" {
  type        = list(string)
  default     = []
  description = "Extra IAM principal ARNs granted ad-hoc data access to the OpenSearch collection (e.g. a personal CLI user for reindex/backfill/debugging). Empty by default (least-privilege). Set in a tfvars file to re-grant CLI access as a version-controlled change instead of manual policy drift."
}

variable "opensearch_max_search_ocu" {
  type        = number
  default     = 8
  description = "Max search OCUs (cost ceiling). Scale-to-zero keeps the idle minimum at 0."
}

variable "opensearch_max_indexing_ocu" {
  type        = number
  default     = 8
  description = "Max indexing OCUs (used during reindex). Scale-to-zero keeps the idle minimum at 0."
}

variable "opensearch_budget_limit_amount" {
  type        = string
  default     = "30"
  description = "Monthly budget (USD) for the OpenSearch/Bedrock billing alarm (~$15 expected)."
}

variable "opensearch_budget_notification_email" {
  type        = string
  default     = ""
  description = "Email for the OpenSearch budget alert. Empty = no budget resource created."
}

# --- OCI self-hosted OpenSearch (opt-in) ---
# Self-hosted OpenSearch on an Oracle Cloud Ampere A1 VM, replacing AWS OpenSearch Serverless
# (which kept ~6.5 OCU warm for the 2.2M vector index even when idle, ~$240/mo vs a ~$15 budget).
# Everything is gated by enable_oci_opensearch, so the default deployment provisions nothing in
# OCI. The OCI provider credentials are non-secret OCIDs + a fingerprint; the private key is read
# from a local file path and never committed.

variable "enable_oci_opensearch" {
  type        = bool
  default     = false
  description = "Provision the self-hosted OpenSearch node on Oracle Cloud (Ampere A1). Off by default. Requires the oci_* provider variables to be set."
}

variable "oci_tenancy_ocid" {
  type        = string
  default     = ""
  description = "OCI tenancy OCID (from the API-key config preview). Non-secret."
}

variable "oci_user_ocid" {
  type        = string
  default     = ""
  description = "OCI user OCID whose API signing key Terraform uses. Non-secret."
}

variable "oci_fingerprint" {
  type        = string
  default     = ""
  description = "Fingerprint of the OCI API signing key uploaded to the user. Non-secret."
}

variable "oci_private_key_path" {
  type        = string
  default     = "~/.oci/oci_api_key.pem"
  description = "Local filesystem path to the OCI API signing PRIVATE key (PEM). Never committed; keep chmod 600."
}

variable "oci_region" {
  type        = string
  default     = "us-sanjose-1"
  description = "OCI home region identifier where the Always-Free / A1 capacity lives."
}

variable "oci_compartment_ocid" {
  type        = string
  default     = ""
  description = "OCI compartment OCID to create resources in. Defaults to the tenancy root compartment (= tenancy OCID) when blank."
}

variable "oci_opensearch_ocpus" {
  type        = number
  default     = 2
  description = "Ampere A1 OCPUs (1 OCPU = 1 physical core). 2 is plenty for serving search; the workload is memory-bound, not CPU-bound."
}

variable "oci_opensearch_memory_gb" {
  type        = number
  default     = 24
  description = "Ampere A1 memory (GB). 24 GB comfortably fits the 2.2M index at fp16. Free-tier cap is 12 GB (needs byte/on_disk quantization); 24 GB requires a Pay-As-You-Go account (~$13/mo)."
}

variable "oci_opensearch_boot_volume_gb" {
  type        = number
  default     = 100
  description = "Boot volume size (GB). Holds the OS + Docker + the OpenSearch data dir (index ~10-16 GB). Free tier includes 200 GB block storage total."
}

variable "oci_admin_cidr" {
  type        = string
  default     = ""
  description = "CIDR allowed to reach SSH (22) and the OpenSearch API (9200) — normally your admin IP as a /32 (e.g. 70.95.245.8/32). Required when enable_oci_opensearch=true."
}

variable "oci_ssh_public_key" {
  type        = string
  default     = ""
  description = "SSH public key authorized for the 'opc' user on the VM. When blank, the module generates a key pair and writes the private key to oci_generated_ssh_key_path (output)."
}

variable "oci_opensearch_admin_password" {
  type        = string
  default     = ""
  sensitive   = true
  description = "Initial admin password for OpenSearch security (OPENSEARCH_INITIAL_ADMIN_PASSWORD). Must meet OpenSearch's strong-password policy. Pass via TF_VAR_oci_opensearch_admin_password or a gitignored *.auto.tfvars — do NOT commit."
}

variable "oci_opensearch_image_ocid" {
  type        = string
  default     = ""
  description = "Optional explicit OCID of an aarch64 Oracle Linux 8/9 image. When blank the module looks up the latest Oracle Linux image for the A1 shape."
}

# --- OpenSearch client transport (for the self-hosted Oracle node cutover) ---

variable "opensearch_endpoint" {
  type        = string
  default     = ""
  description = "Explicit OpenSearch endpoint override for the ECS app. Blank = auto: the OCI node endpoint when opensearch_auth=basic, else the AWS collection endpoint."
}

variable "opensearch_auth" {
  type        = string
  default     = "sigv4"
  description = "OpenSearch transport auth the ECS app uses: sigv4 (Amazon OpenSearch) | basic (self-hosted Oracle node)."
}

variable "opensearch_username" {
  type        = string
  default     = ""
  description = "OpenSearch basic-auth username for the ECS app (opensearch_auth=basic). Non-secret (typically 'admin')."
}

variable "opensearch_password_arn" {
  type        = string
  default     = ""
  description = "Secrets Manager ARN of the OpenSearch basic-auth password, injected into ECS as a secret. Empty when auth=sigv4."
}

variable "opensearch_tls_verify" {
  type        = bool
  default     = true
  description = "ECS app verifies the OpenSearch TLS cert. false only for the self-signed self-hosted node."
}
