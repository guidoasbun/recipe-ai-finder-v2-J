variable "enable" {
  type        = bool
  default     = false
  description = "Master switch. When false the module creates nothing (all resources use count = enable ? 1 : 0)."
}

variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "compartment_ocid" {
  type        = string
  description = "Compartment to create resources in (tenancy root OCID is a valid compartment)."
}

variable "ocpus" {
  type        = number
  default     = 2
  description = "Ampere A1 OCPUs (physical cores)."
}

variable "memory_gb" {
  type        = number
  default     = 24
  description = "Ampere A1 memory in GB."
}

variable "boot_volume_gb" {
  type        = number
  default     = 100
  description = "Boot volume size in GB (holds OS + Docker + OpenSearch data)."
}

variable "admin_cidr" {
  type        = string
  description = "CIDR allowed to reach SSH (22) and OpenSearch (9200). Normally the admin IP /32."
}

variable "ssh_public_key" {
  type        = string
  default     = ""
  description = "Authorized SSH public key. When blank the module generates a key pair."
}

variable "opensearch_admin_password" {
  type        = string
  sensitive   = true
  description = "Initial OpenSearch admin password (OPENSEARCH_INITIAL_ADMIN_PASSWORD)."
}

variable "opensearch_heap_gb" {
  type        = number
  default     = 0
  description = "OpenSearch JVM heap in GB. 0 = auto (~half of memory_gb, capped at 31). Keep <= 50% of RAM so the OS page cache can serve the on-disk vectors."
}

variable "image_ocid" {
  type        = string
  default     = ""
  description = "Optional explicit aarch64 Oracle Linux image OCID. Blank = look up the latest."
}
