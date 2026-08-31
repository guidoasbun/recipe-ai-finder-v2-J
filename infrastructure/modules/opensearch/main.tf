/**
 * OpenSearch Serverless (NextGen) vector search collection for the recipe catalog.
 *
 * Opt-in: everything here is gated by var.enable_opensearch (count = enable ? 1 : 0), so the
 * default deployment provisions nothing and incurs no extra cost. When enabled it creates a
 * VECTORSEARCH collection with scale-to-zero (idle -> 0 OCU) and a bounded max OCU capacity,
 * plus the encryption / network / data-access policies the collection requires.
 */

locals {
  enabled         = var.enable_opensearch
  collection_name = var.collection_name != "" ? var.collection_name : "${var.project_name}-${var.environment}-catalog"
}

# NOTE: account-level OCU capacity limits (the cost ceiling) are NOT settable via the
# Terraform AWS provider yet (hashicorp/terraform-provider-aws issue #41245). Set them once
# out-of-band with the CLI, using var.max_search_ocu / var.max_indexing_ocu as the values:
#
#   aws opensearchserverless update-account-settings \
#     --capacity-limits maxIndexingCapacityInOCU=<max_indexing_ocu>,maxSearchCapacityInOCU=<max_search_ocu>
#
# The billing budget below is the Terraform-managed guardrail; the CLI cap is the hard ceiling.

resource "aws_opensearchserverless_access_policy" "data" {
  count = local.enabled ? 1 : 0
  name  = substr("${local.collection_name}-data", 0, 32)
  type  = "data"

  policy = jsonencode([
    {
      Rules = [
        {
          ResourceType = "index"
          Resource     = ["index/${local.collection_name}/*"]
          Permission = [
            "aoss:CreateIndex",
            "aoss:DescribeIndex",
            "aoss:ReadDocument",
            "aoss:WriteDocument",
            "aoss:UpdateIndex",
            "aoss:DeleteIndex"
          ]
        },
        {
          ResourceType = "collection"
          Resource     = ["collection/${local.collection_name}"]
          Permission = [
            "aoss:CreateCollectionItems",
            "aoss:DescribeCollectionItems",
            "aoss:UpdateCollectionItems"
          ]
        }
      ]
      Principal = [var.task_role_arn]
    }
  ])
}

resource "aws_opensearchserverless_security_policy" "encryption" {
  count = local.enabled ? 1 : 0
  name  = substr("${local.collection_name}-enc", 0, 32)
  type  = "encryption"

  policy = jsonencode({
    Rules = [{
      ResourceType = "collection"
      Resource     = ["collection/${local.collection_name}"]
    }]
    AWSOwnedKey = true
  })
}

resource "aws_opensearchserverless_security_policy" "network" {
  count = local.enabled ? 1 : 0
  name  = substr("${local.collection_name}-net", 0, 32)
  type  = "network"

  policy = jsonencode([{
    Rules = [
      {
        ResourceType = "collection"
        Resource     = ["collection/${local.collection_name}"]
      },
      {
        ResourceType = "dashboard"
        Resource     = ["collection/${local.collection_name}"]
      }
    ]
    AllowFromPublic = true
  }])
}

resource "aws_opensearchserverless_collection" "catalog" {
  count            = local.enabled ? 1 : 0
  name             = local.collection_name
  type             = "VECTORSEARCH"
  standby_replicas = "DISABLED"

  depends_on = [
    aws_opensearchserverless_access_policy.data,
    aws_opensearchserverless_security_policy.encryption,
    aws_opensearchserverless_security_policy.network
  ]

  tags = {
    Name        = local.collection_name
    Environment = var.environment
  }
}

# Billing alarm (Requirement 7.6). A monthly cost budget scoped to the OpenSearch and Bedrock
# services so unexpected spend on the search backend is surfaced. Created only when both the
# feature is enabled and a notification email is provided.
resource "aws_budgets_budget" "opensearch" {
  count        = local.enabled && var.budget_notification_email != "" ? 1 : 0
  name         = "${var.project_name}-${var.environment}-opensearch"
  budget_type  = "COST"
  limit_amount = var.budget_limit_amount
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name = "Service"
    values = [
      "Amazon OpenSearch Service",
      "Amazon Bedrock"
    ]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_notification_email]
  }
}
