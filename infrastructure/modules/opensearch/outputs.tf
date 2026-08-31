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
