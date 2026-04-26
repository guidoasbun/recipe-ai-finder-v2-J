output "execution_role_arn" {
  value = aws_iam_role.execution_role.arn
}

output "task_role_arn" {
  value = aws_iam_role.task_role.arn
}

output "github_actions_role_arn" {
  value = aws_iam_role.github_actions.arn
}
