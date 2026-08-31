output "collection_endpoint" {
  description = "HTTPS endpoint of the serverless collection (empty when disabled). Feeds OPENSEARCH_ENDPOINT."
  value       = var.enable_opensearch ? aws_opensearchserverless_collection.catalog[0].collection_endpoint : ""
}

output "collection_arn" {
  description = "ARN of the serverless collection (empty when disabled)."
  value       = var.enable_opensearch ? aws_opensearchserverless_collection.catalog[0].arn : ""
}

output "collection_name" {
  value = local.collection_name
}

# Account-level OCU limits are not settable via the Terraform AWS provider (issue #41245), so
# emit the exact CLI command using the configured values. This makes max_search_ocu /
# max_indexing_ocu meaningful (run this once after enabling OpenSearch), rather than no-ops.
output "ocu_cap_cli_command" {
  description = "Run once to enforce the OCU cost ceiling (see RUNBOOK §3.2)."
  value = var.enable_opensearch ? format(
    "aws opensearchserverless update-account-settings --capacity-limits maxIndexingCapacityInOCU=%d,maxSearchCapacityInOCU=%d",
    var.max_indexing_ocu, var.max_search_ocu
  ) : ""
}
