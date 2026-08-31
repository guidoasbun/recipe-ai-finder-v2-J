output "users_table_name" {
  value = aws_dynamodb_table.users.name
}

output "recipes_table_name" {
  value = aws_dynamodb_table.recipes.name
}

output "users_table_arn" {
  value = aws_dynamodb_table.users.arn
}

output "recipes_table_arn" {
  value = aws_dynamodb_table.recipes.arn
}

output "catalog_table_name" {
  value = aws_dynamodb_table.catalog.name
}

output "catalog_table_arn" {
  value = aws_dynamodb_table.catalog.arn
}

output "catalog_full_table_name" {
  description = "Name of the full 2.2M catalog table, or empty when not enabled."
  value       = var.enable_catalog_full ? aws_dynamodb_table.catalog_full[0].name : ""
}

output "consent_table_name" {
  value = aws_dynamodb_table.consent.name
}

output "consent_table_arn" {
  value = aws_dynamodb_table.consent.arn
}

output "audit_log_table_name" {
  value = aws_dynamodb_table.audit_log.name
}

output "audit_log_table_arn" {
  value = aws_dynamodb_table.audit_log.arn
}
