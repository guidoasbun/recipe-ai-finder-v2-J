/**
 * Self-hosted OpenSearch on an Oracle Cloud Ampere A1 VM.
 *
 * Replaces AWS OpenSearch Serverless (which held ~6.5 OCU warm for the 2.2M vector index even
 * when idle, ~$240/mo vs a ~$15 budget). This module stands up one always-on VM running a
 * single-node OpenSearch container. The index rebuilds from the DynamoDB source of truth
 * (no re-embedding), so the node holds no unique state that isn't reproducible.
 *
 * Everything is gated by var.enable (count = enable ? 1 : 0): the default deployment provisions
 * nothing in OCI.
 *
 * Network posture: a minimal VCN with a public subnet + internet gateway. The security list
 * allows SSH (22) and the OpenSearch API (9200) ONLY from var.admin_cidr (the operator's IP).
 * The live ECS app cannot reach it yet — the index is built/verified from the operator's laptop;
 * app egress access is solved before the production cutover (ECS runs in public subnets with
 * dynamic IPs, so a stable-egress approach — e.g. a NAT gateway EIP — is decided separately).
 */

terraform {
  required_providers {
    oci = {
      source = "oracle/oci"
    }
    tls = {
      source = "hashicorp/tls"
    }
  }
}

locals {
  enabled  = var.enable
  name     = "${var.project_name}-${var.environment}-opensearch"
  data_dir = "/opt/opensearch/data"
  # Heap: explicit value, else ~half of RAM capped at 31 GB (compressed-oops ceiling). Keeping
  # heap <= 50% of RAM leaves the rest for the OS page cache, which serves the on-disk vectors
  # in byte/on_disk quantization mode.
  heap_gb = var.opensearch_heap_gb > 0 ? var.opensearch_heap_gb : min(floor(var.memory_gb / 2), 31)
  # Root compartment == tenancy OCID is a valid compartment; caller passes whichever it wants.
  compartment_ocid = var.compartment_ocid
}

# Guardrails: fail fast at plan time on the two easy-to-miss requirements.
resource "terraform_data" "preconditions" {
  count = local.enabled ? 1 : 0
  lifecycle {
    precondition {
      condition     = var.admin_cidr != ""
      error_message = "enable_oci_opensearch=true requires oci_admin_cidr (your admin IP as a /32, e.g. 70.95.245.8/32)."
    }
    precondition {
      condition     = var.opensearch_admin_password != ""
      error_message = "enable_oci_opensearch=true requires oci_opensearch_admin_password (pass via TF_VAR_oci_opensearch_admin_password; never commit it)."
    }
  }
}

# ── SSH key (generate one when the caller didn't supply a public key) ─────────
resource "tls_private_key" "ssh" {
  count     = local.enabled && var.ssh_public_key == "" ? 1 : 0
  algorithm = "RSA"
  rsa_bits  = 4096
}

locals {
  ssh_public_key = local.enabled ? (
    var.ssh_public_key != "" ? var.ssh_public_key : tls_private_key.ssh[0].public_key_openssh
  ) : ""
}

# ── Availability domain + image lookup ────────────────────────────────────────
data "oci_identity_availability_domains" "ads" {
  count          = local.enabled ? 1 : 0
  compartment_id = local.compartment_ocid
}

# Latest Oracle Linux 9 aarch64 image for the A1 shape (used when no explicit image_ocid given).
data "oci_core_images" "ol" {
  count                    = local.enabled && var.image_ocid == "" ? 1 : 0
  compartment_id           = local.compartment_ocid
  operating_system         = "Oracle Linux"
  operating_system_version = "9"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

locals {
  image_ocid = local.enabled ? (
    var.image_ocid != "" ? var.image_ocid : data.oci_core_images.ol[0].images[0].id
  ) : ""
  ad_name = local.enabled ? data.oci_identity_availability_domains.ads[0].availability_domains[0].name : ""
}

# ── Networking: VCN + public subnet + internet gateway + route + security list ─
resource "oci_core_vcn" "this" {
  count          = local.enabled ? 1 : 0
  compartment_id = local.compartment_ocid
  display_name   = "${local.name}-vcn"
  cidr_blocks    = ["10.10.0.0/16"]
  dns_label      = "recipeai"
}

resource "oci_core_internet_gateway" "this" {
  count          = local.enabled ? 1 : 0
  compartment_id = local.compartment_ocid
  vcn_id         = oci_core_vcn.this[0].id
  display_name   = "${local.name}-igw"
  enabled        = true
}

resource "oci_core_route_table" "this" {
  count          = local.enabled ? 1 : 0
  compartment_id = local.compartment_ocid
  vcn_id         = oci_core_vcn.this[0].id
  display_name   = "${local.name}-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this[0].id
  }
}

resource "oci_core_security_list" "this" {
  count          = local.enabled ? 1 : 0
  compartment_id = local.compartment_ocid
  vcn_id         = oci_core_vcn.this[0].id
  display_name   = "${local.name}-sl"

  # Allow all egress (the node pulls the Docker image, OS updates).
  egress_security_rules {
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    protocol         = "all"
  }

  # SSH (22) from the admin IP only.
  ingress_security_rules {
    protocol    = "6" # TCP
    source      = var.admin_cidr
    source_type = "CIDR_BLOCK"
    description = "SSH from admin IP"
    tcp_options {
      min = 22
      max = 22
    }
  }

  # OpenSearch API (9200) from the admin IP only. Reindex/backfill/verify run from the operator's
  # laptop. Broaden this (or add the app's stable egress CIDR) at cutover.
  ingress_security_rules {
    protocol    = "6" # TCP
    source      = var.admin_cidr
    source_type = "CIDR_BLOCK"
    description = "OpenSearch API from admin IP"
    tcp_options {
      min = 9200
      max = 9200
    }
  }
}

resource "oci_core_subnet" "public" {
  count                      = local.enabled ? 1 : 0
  compartment_id             = local.compartment_ocid
  vcn_id                     = oci_core_vcn.this[0].id
  cidr_block                 = "10.10.1.0/24"
  display_name               = "${local.name}-public-subnet"
  route_table_id             = oci_core_route_table.this[0].id
  security_list_ids          = [oci_core_security_list.this[0].id]
  prohibit_public_ip_on_vnic = false
  dns_label                  = "public"
}

# ── Compute: Ampere A1 Flex VM ────────────────────────────────────────────────
resource "oci_core_instance" "this" {
  count               = local.enabled ? 1 : 0
  compartment_id      = local.compartment_ocid
  availability_domain = local.ad_name
  display_name        = local.name
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_gb
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public[0].id
    assign_public_ip = true
    display_name     = "${local.name}-vnic"
  }

  source_details {
    source_type             = "image"
    source_id               = local.image_ocid
    boot_volume_size_in_gbs = var.boot_volume_gb
  }

  metadata = {
    ssh_authorized_keys = local.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
      heap_gb        = local.heap_gb
      admin_password = var.opensearch_admin_password
      data_dir       = local.data_dir
    }))
  }

  # The admin password flows through user_data; changing it requires a rebuild (recreate) since
  # OPENSEARCH_INITIAL_ADMIN_PASSWORD only applies on first container start anyway.
  lifecycle {
    ignore_changes = [metadata["user_data"]]
  }
}
