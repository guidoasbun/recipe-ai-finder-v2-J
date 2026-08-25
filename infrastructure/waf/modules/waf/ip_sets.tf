# WAF IP Sets
#
# IP sets are standalone resources with no lifecycle dependency on the Web ACL,
# allowing independent updates without replacing the ACL.

# --- IPv4 Allow-List ---
resource "aws_wafv2_ip_set" "allow_list" {
  name               = "${var.project_name}-${var.environment}-allow-list"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.allowed_ips

  tags = {
    Name        = "${var.project_name}-${var.environment}-allow-list"
    Environment = var.environment
    Project     = var.project_name
  }
}

# --- IPv6 Allow-List ---
resource "aws_wafv2_ip_set" "allow_list_v6" {
  name               = "${var.project_name}-${var.environment}-allow-list-v6"
  scope              = "REGIONAL"
  ip_address_version = "IPV6"
  addresses          = var.allowed_ips_v6

  tags = {
    Name        = "${var.project_name}-${var.environment}-allow-list-v6"
    Environment = var.environment
    Project     = var.project_name
  }
}

# --- IPv4 Block-List ---
resource "aws_wafv2_ip_set" "block_list" {
  name               = "${var.project_name}-${var.environment}-block-list"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.blocked_ips

  tags = {
    Name        = "${var.project_name}-${var.environment}-block-list"
    Environment = var.environment
    Project     = var.project_name
  }
}

# --- IPv6 Block-List ---
resource "aws_wafv2_ip_set" "block_list_v6" {
  name               = "${var.project_name}-${var.environment}-block-list-v6"
  scope              = "REGIONAL"
  ip_address_version = "IPV6"
  addresses          = var.blocked_ips_v6

  tags = {
    Name        = "${var.project_name}-${var.environment}-block-list-v6"
    Environment = var.environment
    Project     = var.project_name
  }
}
