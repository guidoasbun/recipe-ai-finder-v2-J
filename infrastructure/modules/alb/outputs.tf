output "alb_dns" {
  value = aws_lb.main.dns_name
}

output "backend_tg_arn" {
  value = aws_lb_target_group.backend.arn
}

output "frontend_tg_arn" {
  value = aws_lb_target_group.frontend.arn
}

output "alb_security_group_id" {
  value = tolist(aws_lb.main.security_groups)[0]
}

output "alb_arn" {
  value       = aws_lb.main.arn
  description = "ARN of the Application Load Balancer"
}

output "alb_arn_suffix" {
  value       = aws_lb.main.arn_suffix
  description = "ALB ARN suffix (app/<name>/<id>) — the LoadBalancer dimension for AWS/ApplicationELB CloudWatch metrics."
}

output "backend_tg_arn_suffix" {
  value       = aws_lb_target_group.backend.arn_suffix
  description = "Backend target-group ARN suffix (targetgroup/<name>/<id>) — the TargetGroup dimension for AWS/ApplicationELB per-target metrics (5XX, healthy hosts, latency)."
}
