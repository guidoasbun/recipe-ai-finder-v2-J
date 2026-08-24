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
